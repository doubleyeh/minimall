package com.minimall.sys.service;

import com.minimall.sys.api.dto.MenuTreeNode;
import com.minimall.sys.api.dto.RoleCreateRequest;
import com.minimall.sys.api.dto.RoleMenuGrantRequest;
import com.minimall.sys.api.dto.RoleView;
import com.minimall.common.PageResult;

import java.util.List;

/**
 * 角色与角色授权(架构文档 5.2、5.2.1、5.3、5.6)。
 *
 * <p>租户隔离:角色表走标准 tenant_id 等值过滤(4.6.1),服务层不需要额外拼租户条件。
 */
public interface RoleService {

    PageResult<RoleView> page(String roleName, Integer status, int pageNo, int pageSize);

    Long create(RoleCreateRequest request);

    /**
     * 修改角色。{@code is_default = 1} 的默认管理员角色只允许改名称等展示字段,
     * 不允许把 dataScope 之外的授权语义改乱(它的菜单由套餐同步维护,见 5.2.1)。
     */
    void update(Long roleId, RoleCreateRequest request);

    /**
     * 删除角色。校验见 5.6:默认管理员角色不允许删除;有用户持有时拒绝删除;级联清 sys_role_menu/sys_role_dept/sys_user_role。
     */
    void delete(Long roleId);

    void changeStatus(Long roleId, int status);

    /**
     * 授权候选菜单树:按当前租户套餐过滤后的菜单树(5.2.1 规则 1),不含平台专用菜单。
     */
    List<MenuTreeNode> grantableMenuTree(Long roleId);

    /**
     * 该角色已授权的菜单 ID 列表(用于授权界面回显)。
     */
    List<Long> grantedMenuIds(Long roleId);

    /**
     * 保存角色菜单授权。
     *
     * <p>两条硬约束:
     * <ol>
     *   <li>按 5.2.1 规则 2 校验每个 menuId 都在候选集内,越界抛 MENU_OUT_OF_PACKAGE</li>
     *   <li>默认管理员角色的菜单不接受人工增删(5.2.1 规则 3),对它调用本方法直接拒绝</li>
     * </ol>
     * 成功后必须 INCR 该租户的权限版本号(5.5),使新权限在下一个请求生效。
     */
    void grantMenus(Long roleId, RoleMenuGrantRequest request);
}
