package com.minimall.sys.service;

import com.minimall.sys.api.dto.DictDataSaveRequest;
import com.minimall.sys.api.dto.DictDataView;
import com.minimall.sys.api.dto.DictItemView;
import com.minimall.sys.api.dto.DictTypeSaveRequest;
import com.minimall.sys.api.dto.DictTypeView;
import com.minimall.common.PageResult;

import java.util.List;

/**
 * 字典维护与读取(架构文档 5.1)。
 *
 * <p>{@code sys_dict_type}/{@code sys_dict_data} 是**平台级数据**(4.6.1):全局共用、不分租户。
 * 语义上它属于"平台配置",维护入口的权限码挂在 {@code is_platform = 1} 的菜单下 ——
 * 租户侧角色授权时拿不到平台菜单,所以租户管理员天然改不了字典(5.2.1)。
 *
 * <p>业务侧只需要读,见 {@link #items(String)}:它走 Redis 缓存,
 * 因为字典是"读极多写极少"的配置数据。
 */
public interface DictService {

    /**
     * 字典类型分页。{@code dictType}/{@code dictName} 都是可选模糊条件。
     */
    PageResult<DictTypeView> pageTypes(String dictType, String dictName, int pageNo, int pageSize);

    /**
     * 新增字典类型。
     *
     * @return 新类型 ID
     */
    Long createType(DictTypeSaveRequest request);

    /**
     * 修改字典类型。只允许改名称:**编码不允许改** ——
     * 改了会让已有的 {@code sys_dict_data.dictType} 全部悬空(历史取值查不到字典)。
     */
    void updateType(Long typeId, DictTypeSaveRequest request);

    /**
     * 删除字典类型,并清理它的全部字典项与缓存。
     */
    void deleteType(Long typeId);

    /**
     * 某类型下的全部字典项(管理端维护用,含主键)。未配置时返回空列表,不报错。
     */
    List<DictDataView> listData(String dictType);

    /**
     * 新增字典项。要求 {@code dictType} 已存在,且同类型下 {@code dictValue} 不重复。
     *
     * @return 新字典项 ID
     */
    Long createData(DictDataSaveRequest request);

    /**
     * 修改字典项。所属字典类型不允许改(要换类型请先删再建)。
     */
    void updateData(Long dataId, DictDataSaveRequest request);

    /**
     * 删除字典项。
     */
    void deleteData(Long dataId);

    /**
     * 业务端只读:某类型的下拉项({@code label}/{@code value}),走缓存。
     *
     * <p>类型不存在时返回空列表而不是 404:调用方通常是在渲染一个下拉框,
     * 字典没配好应该表现为"这个下拉是空的",而不是让整个页面接口报错。
     */
    List<DictItemView> items(String dictType);
}
