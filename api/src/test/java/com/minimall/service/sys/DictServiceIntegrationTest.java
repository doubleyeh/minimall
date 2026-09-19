package com.minimall.service.sys;

import com.minimall.api.sys.dto.DictDataSaveRequest;
import com.minimall.api.sys.dto.DictDataView;
import com.minimall.api.sys.dto.DictItemView;
import com.minimall.api.sys.dto.DictTypeSaveRequest;
import com.minimall.api.sys.dto.DictTypeView;
import com.minimall.common.BusinessException;
import com.minimall.common.PageResult;
import com.minimall.domain.sys.SysDictData;
import com.minimall.domain.sys.repository.SysDictDataRepository;
import com.minimall.domain.sys.repository.SysDictTypeRepository;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.dict.DictCacheService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 字典模块行为验证(架构文档 5.1、5.5)。
 *
 * <p>重点不在 CRUD 本身,而在两条容易写错、又不会立刻暴露的规则:
 * ①**写操作必须清缓存**——漏了的话前端会一直显示旧标签(而且有 1 小时 TTL,排查起来像"没保存成功");
 * ②**删类型要连带清掉子项**——否则 {@code sys_dict_data} 里会留下永远查不到的孤儿行,
 * 下次新建同名类型时它们会突然"复活"。
 *
 * <p>缓存用例刻意用"绕过 service 直接删库"的方式制造不一致:这是唯一能证明
 * "第二次读真的走了缓存"的手段(不是碰巧又查了一次库)。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("dev")
class DictServiceIntegrationTest {

    @Autowired
    private DictService dictService;

    @Autowired
    private SysDictTypeRepository typeRepository;

    @Autowired
    private SysDictDataRepository dataRepository;

    @Autowired
    private DictCacheService dictCacheService;

    /** 每次用例用独立的编码,避免与其他用例/种子数据互相干扰。 */
    private String dictType;

    @BeforeEach
    void setUp() {
        dictType = "it_dict_" + Long.toString(System.nanoTime(), 36);
        AuditContext.bind(new AuditContext(1L, 1L, "127.0.0.1", "dict-it"));
    }

    @AfterEach
    void tearDown() {
        AuditContext.clear();
        dictCacheService.evict(dictType);
        dataRepository.deleteAll(dataRepository.findByDictTypeOrderBySortOrderAscIdAsc(dictType));
        typeRepository.findByDictType(dictType).ifPresent(typeRepository::delete);
    }

    @Test
    @DisplayName("用例1:类型与字典项的基本读写,列表按 sortOrder 升序")
    void createAndReadBack() {
        Long typeId = dictService.createType(new DictTypeSaveRequest(dictType, "用例字典"));
        assertThat(typeId).isNotNull();

        dictService.createData(new DictDataSaveRequest(dictType, "标签B", "2", 20));
        dictService.createData(new DictDataSaveRequest(dictType, "标签A", "1", 10));

        List<DictDataView> data = dictService.listData(dictType);
        assertThat(data).extracting(DictDataView::dictValue).containsExactly("1", "2");

        PageResult<DictTypeView> page = dictService.pageTypes(dictType, null, 1, 10);
        assertThat(page.list()).singleElement()
                .satisfies(view -> {
                    assertThat(view.dictType()).isEqualTo(dictType);
                    assertThat(view.dictName()).isEqualTo("用例字典");
                    // dataCount 让列表页不用逐行再查一次
                    assertThat(view.dataCount()).isEqualTo(2);
                });
    }

    @Test
    @DisplayName("用例2:类型编码不可修改 —— 改了会让已有字典项全部悬空")
    void dictTypeCodeIsImmutable() {
        dictService.createType(new DictTypeSaveRequest(dictType, "用例字典"));

        assertThatThrownBy(() -> dictService.updateType(
                typeRepository.findByDictType(dictType).orElseThrow().getId(),
                new DictTypeSaveRequest(dictType + "_renamed", "新名字")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许修改");

        // 只改名字是允许的
        dictService.updateType(typeRepository.findByDictType(dictType).orElseThrow().getId(),
                new DictTypeSaveRequest(dictType, "改过的名字"));
        assertThat(typeRepository.findByDictType(dictType).orElseThrow().getDictName()).isEqualTo("改过的名字");
    }

    @Test
    @DisplayName("用例3:编码与取值都不允许重复")
    void rejectDuplicates() {
        dictService.createType(new DictTypeSaveRequest(dictType, "用例字典"));
        assertThatThrownBy(() -> dictService.createType(new DictTypeSaveRequest(dictType, "重复编码")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");

        dictService.createData(new DictDataSaveRequest(dictType, "标签A", "1", 10));
        assertThatThrownBy(() -> dictService.createData(new DictDataSaveRequest(dictType, "标签A2", "1", 20)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典值");
    }

    @Test
    @DisplayName("用例4:字典项必须挂在已存在的类型下 —— 否则会产生永远查不到的孤儿项")
    void dataRequiresExistingType() {
        assertThatThrownBy(() -> dictService.createData(
                new DictDataSaveRequest(dictType + "_ghost", "标签", "1", 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字典类型不存在");
    }

    @Test
    @DisplayName("用例5:删除类型连带清掉子项,不留孤儿数据")
    void deleteTypeCascadesData() {
        Long typeId = dictService.createType(new DictTypeSaveRequest(dictType, "用例字典"));
        dictService.createData(new DictDataSaveRequest(dictType, "标签A", "1", 10));
        dictService.createData(new DictDataSaveRequest(dictType, "标签B", "2", 20));

        dictService.deleteType(typeId);

        assertThat(typeRepository.findByDictType(dictType)).isEmpty();
        assertThat(dataRepository.findByDictTypeOrderBySortOrderAscIdAsc(dictType)).isEmpty();
        // 缓存也要一起清,否则删完还能从缓存里读出旧下拉项
        assertThat(dictService.items(dictType)).isEmpty();
    }

    @Test
    @DisplayName("用例6:读取走缓存;写操作立即失效缓存(不能等 TTL)")
    void cacheIsUsedAndInvalidatedOnWrite() {
        dictService.createType(new DictTypeSaveRequest(dictType, "用例字典"));
        dictService.createData(new DictDataSaveRequest(dictType, "标签A", "1", 10));

        assertThat(dictService.items(dictType)).extracting(DictItemView::value).containsExactly("1");

        // 绕过 service 直接删库(模拟"另一个节点改了库但缓存没动"),此时缓存里还是旧值
        dataRepository.deleteAll(dataRepository.findByDictTypeOrderBySortOrderAscIdAsc(dictType));
        assertThat(dictService.items(dictType))
                .as("第二次读取必须命中缓存,否则这个断言会看到空列表")
                .extracting(DictItemView::value).containsExactly("1");

        // 主动失效后立刻读到新状态 —— 这就是"写操作要 evict"的实证
        dictCacheService.evict(dictType);
        assertThat(dictService.items(dictType)).isEmpty();
    }

    @Test
    @DisplayName("用例7:查不存在的类型返回空列表而不是报错(不能让下拉框把页面打崩)")
    void unknownTypeReturnsEmptyInsteadOfError() {
        assertThat(dictService.items("it_dict_not_exists_" + dictType)).isEmpty();
    }

    @Test
    @DisplayName("用例8:新增字典项的写入操作也会失效缓存")
    void writeInvalidatesCache() {
        dictService.createType(new DictTypeSaveRequest(dictType, "用例字典"));
        assertThat(dictService.items(dictType)).isEmpty(); // 空结果也被缓存住

        dictService.createData(new DictDataSaveRequest(dictType, "标签A", "1", 10));

        assertThat(dictService.items(dictType))
                .as("新增后必须立刻能读到,否则用户会以为没保存成功")
                .extracting(DictItemView::value).containsExactly("1");
    }

    @Test
    @DisplayName("用例9:删除字典项后缓存立即失效")
    void deleteDataInvalidatesCache() {
        dictService.createType(new DictTypeSaveRequest(dictType, "用例字典"));
        Long dataId = dictService.createData(new DictDataSaveRequest(dictType, "标签A", "1", 10));
        assertThat(dictService.items(dictType)).hasSize(1);

        dictService.deleteData(dataId);

        assertThat(dictService.items(dictType)).isEmpty();
        assertThat(dataRepository.findById(dataId)).isEmpty();
    }

    @Test
    @DisplayName("用例10:修改字典项后按新标签返回")
    void updateDataRefreshesCache() {
        dictService.createType(new DictTypeSaveRequest(dictType, "用例字典"));
        Long dataId = dictService.createData(new DictDataSaveRequest(dictType, "旧标签", "1", 10));

        dictService.updateData(dataId, new DictDataSaveRequest(dictType, "新标签", "1", 10));

        assertThat(dictService.items(dictType)).singleElement()
                .satisfies(item -> assertThat(item.label()).isEqualTo("新标签"));
    }

    @Test
    @DisplayName("用例11:同类型下改字典值时重复校验生效(与自己比较要放过)")
    void updateDataAllowsSameValueOnSelf() {
        dictService.createType(new DictTypeSaveRequest(dictType, "用例字典"));
        Long dataId = dictService.createData(new DictDataSaveRequest(dictType, "标签A", "1", 10));

        // 值不变、只改标签:不能因为"同类型下值已存在"而拒绝(那条记录就是它自己)
        dictService.updateData(dataId, new DictDataSaveRequest(dictType, "标签A改", "1", 10));

        assertThat(dictService.items(dictType)).singleElement()
                .satisfies(item -> assertThat(item.label()).isEqualTo("标签A改"));
    }

    @Test
    @DisplayName("用例12:种子里的基础字典可读(sys_common_status)")
    void seedDictReadable() {
        assertThat(dictService.items("sys_common_status"))
                .extracting(DictItemView::value).containsExactly("1", "0");
    }

    @Test
    @DisplayName("用例13:对不存在的字典项做修改/删除返回 404 语义,而不是静默成功")
    void missingDataRowIsNotFound() {
        assertThatThrownBy(() -> dictService.deleteData(-1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }
}
