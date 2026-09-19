package com.minimall.sys.service.impl;

import com.minimall.sys.api.dto.DictDataSaveRequest;
import com.minimall.sys.api.dto.DictDataView;
import com.minimall.sys.api.dto.DictItemView;
import com.minimall.sys.api.dto.DictTypeSaveRequest;
import com.minimall.sys.api.dto.DictTypeView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.sys.domain.QSysDictType;
import com.minimall.sys.domain.SysDictData;
import com.minimall.sys.domain.SysDictType;
import com.minimall.sys.domain.repository.SysDictDataRepository;
import com.minimall.sys.domain.repository.SysDictTypeRepository;
import com.minimall.infra.dict.DictCacheService;
import com.minimall.sys.service.DictService;
import com.querydsl.core.BooleanBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 字典维护与读取实现(架构文档 5.1、5.5)。
 *
 * <p><b>写操作与缓存失效必须成对出现</b>:这个类里每一个写方法(新增/修改/删除,类型与数据都算)
 * 都要调用 {@code dictCacheService.evict(...)}。漏掉一个,前端就会继续显示旧标签,
 * 而且 TTL 是 1 小时,排查起来像"配置没保存成功"。所以这里刻意把 evict 紧挨着写操作写。
 */
@Service
@Transactional
public class DictServiceImpl implements DictService {

    private static final Logger log = LoggerFactory.getLogger(DictServiceImpl.class);

    private final SysDictTypeRepository typeRepository;
    private final SysDictDataRepository dataRepository;
    private final DictCacheService dictCacheService;

    public DictServiceImpl(SysDictTypeRepository typeRepository,
                           SysDictDataRepository dataRepository,
                           DictCacheService dictCacheService) {
        this.typeRepository = typeRepository;
        this.dataRepository = dataRepository;
        this.dictCacheService = dictCacheService;
    }

    @Override
    public PageResult<DictTypeView> pageTypes(String dictType, String dictName, int pageNo, int pageSize) {
        QSysDictType qType = QSysDictType.sysDictType;
        BooleanBuilder where = new BooleanBuilder();
        if (dictType != null && !dictType.isBlank()) {
            where.and(qType.dictType.contains(dictType));
        }
        if (dictName != null && !dictName.isBlank()) {
            where.and(qType.dictName.contains(dictName));
        }
        Page<SysDictType> page = typeRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1)));
        List<DictTypeView> views = page.getContent().stream()
                .map(type -> new DictTypeView(type.getId(), type.getDictType(), type.getDictName(),
                        dataRepository.countByDictType(type.getDictType()), type.getCreateTime()))
                .toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public Long createType(DictTypeSaveRequest request) {
        String dictType = trim(request.dictType());
        if (typeRepository.existsByDictType(dictType)) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "字典类型编码已存在");
        }
        SysDictType type = new SysDictType();
        type.setDictType(dictType);
        type.setDictName(trim(request.dictName()));
        Long id = typeRepository.save(type).getId();
        // 新建类型时它的缓存多半是"空结果"(有人先查过后台才建),必须一起清掉
        dictCacheService.evict(dictType);
        return id;
    }

    @Override
    public void updateType(Long typeId, DictTypeSaveRequest request) {
        SysDictType type = loadType(typeId);
        String dictType = trim(request.dictType());
        if (!type.getDictType().equals(dictType)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "字典类型编码不允许修改,请新建类型后迁移数据");
        }
        type.setDictName(trim(request.dictName()));
        dictCacheService.evict(dictType);
    }

    @Override
    public void deleteType(Long typeId) {
        SysDictType type = loadType(typeId);
        long removed = dataRepository.deleteByDictType(type.getDictType());
        typeRepository.delete(type);
        dictCacheService.evict(type.getDictType());
        // 级联删掉的字典项数量要留痕:操作日志只记了"删了哪个类型",
        // 事后追查"某个取值怎么没字典了"时需要这条日志
        log.info("删除字典类型 dictType={} 同时清理字典项 {} 条", type.getDictType(), removed);
    }

    @Override
    public List<DictDataView> listData(String dictType) {
        return dataRepository.findByDictTypeOrderBySortOrderAscIdAsc(dictType).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public Long createData(DictDataSaveRequest request) {
        String dictType = trim(request.dictType());
        requireTypeExists(dictType);
        String dictValue = trim(request.dictValue());
        if (dataRepository.existsByDictTypeAndDictValue(dictType, dictValue)) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "该字典类型下已存在相同的字典值");
        }
        SysDictData data = new SysDictData();
        data.setDictType(dictType);
        data.setDictLabel(trim(request.dictLabel()));
        data.setDictValue(dictValue);
        data.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        Long id = dataRepository.save(data).getId();
        dictCacheService.evict(dictType);
        return id;
    }

    @Override
    public void updateData(Long dataId, DictDataSaveRequest request) {
        SysDictData data = loadData(dataId);
        if (!data.getDictType().equals(trim(request.dictType()))) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "字典项所属类型不允许修改");
        }
        String dictValue = trim(request.dictValue());
        if (dataRepository.existsByDictTypeAndDictValueAndIdNot(data.getDictType(), dictValue, dataId)) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "该字典类型下已存在相同的字典值");
        }
        data.setDictLabel(trim(request.dictLabel()));
        data.setDictValue(dictValue);
        if (request.sortOrder() != null) {
            data.setSortOrder(request.sortOrder());
        }
        dictCacheService.evict(data.getDictType());
    }

    @Override
    public void deleteData(Long dataId) {
        SysDictData data = loadData(dataId);
        String dictType = data.getDictType();
        dataRepository.delete(data);
        dictCacheService.evict(dictType);
    }

    @Override
    public List<DictItemView> items(String dictType) {
        String key = trim(dictType);
        return dictCacheService.get(key).orElseGet(() -> loadAndCache(key));
    }

    private List<DictItemView> loadAndCache(String dictType) {
        List<DictItemView> items = dataRepository.findByDictTypeOrderBySortOrderAscIdAsc(dictType).stream()
                .map(data -> new DictItemView(data.getDictLabel(), data.getDictValue()))
                .toList();
        dictCacheService.put(dictType, items);
        return items;
    }

    private SysDictType loadType(Long typeId) {
        return typeRepository.findById(typeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "字典类型不存在"));
    }

    private SysDictData loadData(Long dataId) {
        return dataRepository.findById(dataId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "字典项不存在"));
    }

    private void requireTypeExists(String dictType) {
        if (!typeRepository.existsByDictType(dictType)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "字典类型不存在:" + dictType);
        }
    }

    private DictDataView toView(SysDictData data) {
        return new DictDataView(data.getId(), data.getDictType(), data.getDictLabel(),
                data.getDictValue(), data.getSortOrder());
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
