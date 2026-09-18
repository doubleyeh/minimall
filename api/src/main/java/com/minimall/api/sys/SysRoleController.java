package com.minimall.api.sys;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.api.sys.dto.MenuTreeNode;
import com.minimall.api.sys.dto.RoleCreateRequest;
import com.minimall.api.sys.dto.RoleMenuGrantRequest;
import com.minimall.api.sys.dto.RoleView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.service.sys.RoleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.minimall.infra.audit.AuditLog;

/**
 * 角色管理接口(架构文档 5.2.1、5.6)。
 *
 * <p>授权相关的两个接口是本方案 P0 的一部分:
 * <ul>
 *   <li>{@code GET /{roleId}/menus/grantable} 返回套餐范围内的候选菜单树</li>
 *   <li>{@code PUT /{roleId}/menus} 保存前服务端二次校验,越界直接拒绝</li>
 * </ul>
 * 少了这两个约束,4.8 的套餐降级收回就会被"管理员手动勾的套餐外菜单"绕过。
 */
@RestController
@RequestMapping("/system/roles")
public class SysRoleController {

    private final RoleService roleService;

    public SysRoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @SaCheckPermission("system:role:list")
    public ApiResponse<PageResult<RoleView>> page(@RequestParam(required = false) String roleName,
                                                  @RequestParam(required = false) Integer status,
                                                  @RequestParam(defaultValue = "1") int pageNo,
                                                  @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(roleService.page(roleName, status, pageNo, pageSize));
    }

    @AuditLog(module = "角色管理", permCode = "system:role:create")
    @PostMapping
    @SaCheckPermission("system:role:create")
    public ApiResponse<Long> create(@Valid @RequestBody RoleCreateRequest request) {
        return ApiResponse.ok(roleService.create(request));
    }

    @AuditLog(module = "角色管理", permCode = "system:role:update")
    @PutMapping("/{roleId}")
    @SaCheckPermission("system:role:update")
    public ApiResponse<Void> update(@PathVariable Long roleId, @Valid @RequestBody RoleCreateRequest request) {
        roleService.update(roleId, request);
        return ApiResponse.ok();
    }

    @AuditLog(module = "角色管理", permCode = "system:role:delete")
    @DeleteMapping("/{roleId}")
    @SaCheckPermission("system:role:delete")
    public ApiResponse<Void> delete(@PathVariable Long roleId) {
        roleService.delete(roleId);
        return ApiResponse.ok();
    }

    @AuditLog(module = "角色管理", permCode = "system:role:status")
    @PutMapping("/{roleId}/status")
    @SaCheckPermission("system:role:status")
    public ApiResponse<Void> changeStatus(@PathVariable Long roleId, @RequestParam int status) {
        roleService.changeStatus(roleId, status);
        return ApiResponse.ok();
    }

    /**
     * 授权候选菜单树(套餐范围内的交集,不含平台专用菜单)。
     */
    @GetMapping("/{roleId}/menus/grantable")
    @SaCheckPermission("system:role:grant")
    public ApiResponse<List<MenuTreeNode>> grantableMenus(@PathVariable Long roleId) {
        return ApiResponse.ok(roleService.grantableMenuTree(roleId));
    }

    /**
     * 该角色当前已授权的菜单 ID(授权界面回显用)。
     */
    @GetMapping("/{roleId}/menus")
    @SaCheckPermission("system:role:grant")
    public ApiResponse<List<Long>> grantedMenus(@PathVariable Long roleId) {
        return ApiResponse.ok(roleService.grantedMenuIds(roleId));
    }

    /**
     * 保存授权。越界的 menuId 会让整个请求失败(不静默过滤),避免"界面显示已授权、实际没生效"。
     */
    @AuditLog(module = "角色管理", permCode = "system:role:grant")
    @PutMapping("/{roleId}/menus")
    @SaCheckPermission("system:role:grant")
    public ApiResponse<Void> grantMenus(@PathVariable Long roleId,
                                        @Valid @RequestBody RoleMenuGrantRequest request) {
        roleService.grantMenus(roleId, request);
        return ApiResponse.ok();
    }
}
