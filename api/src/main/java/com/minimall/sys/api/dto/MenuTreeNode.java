package com.minimall.sys.api.dto;

import java.util.List;

/**
 * 菜单树节点(架构文档 5.2.1)。
 *
 * <p>返回给角色授权界面的菜单树**必须是套餐过滤后的候选集**:按租户 {@code package_id} 查
 * {@code sys_package_menu} 取交集,且永远不含 {@code is_platform = 1} 的平台专用菜单。
 * 否则租户管理员把套餐外菜单勾给自定义角色,4.8 的降级收回就会漏掉这些菜单。
 */
public record MenuTreeNode(
        Long id,
        Long parentId,
        String menuName,
        Integer menuType,
        String routePath,
        String permCode,
        Integer sortOrder,
        Integer status,
        List<MenuTreeNode> children
) {
}
