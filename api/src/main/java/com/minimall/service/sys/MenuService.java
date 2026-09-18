package com.minimall.service.sys;

import com.minimall.api.sys.dto.MenuSaveRequest;
import com.minimall.api.sys.dto.MenuTreeNode;

import java.util.List;

/**
 * 菜单/权限点维护(架构文档 5.1、5.2、5.6)。
 *
 * <p>{@code sys_menu} 是平台级表(4.6.1):不分租户、不参与租户过滤与数据权限,
 * 只有平台超管能维护。租户侧的"能用哪些菜单"由套餐 + 角色授权两层决定。
 *
 * <p>菜单的任何变更(新增/改 perm_code/禁用/删除)都要按 5.5 失效相关租户的权限缓存:
 * 权限码变了却没失效缓存,等于老权限还能继续用。
 */
public interface MenuService {

    /**
     * 全量菜单树(平台超管用,含 {@code is_platform = 1} 的平台专用菜单)。
     */
    List<MenuTreeNode> tree(Integer status, Integer menuType);

    /**
     * 新增菜单。校验:menuType=3(按钮)必须带 permCode;permCode 全局唯一(靠唯一索引兜底)。
     *
     * @return 新菜单 ID
     */
    Long create(MenuSaveRequest request);

    /**
     * 修改菜单。校验:不能把菜单挂到自己的子孙下(成环);改 permCode 时如果已发布给租户,
     * 要同时失效权限缓存。
     */
    void update(Long menuId, MenuSaveRequest request);

    /**
     * 删除菜单(5.6):有子菜单时拒绝;被套餐或角色引用时**允许删除但要级联清理**
     * {@code sys_package_menu} 与 {@code sys_role_menu},并对所有租户 INCR 权限版本号。
     */
    void delete(Long menuId);
}
