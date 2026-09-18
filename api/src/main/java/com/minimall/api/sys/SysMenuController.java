package com.minimall.api.sys;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.api.sys.dto.MenuSaveRequest;
import com.minimall.api.sys.dto.MenuTreeNode;
import com.minimall.common.ApiResponse;
import com.minimall.service.sys.MenuService;
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
 * 菜单/权限点维护接口(平台级,只有平台超管可用)。
 *
 * <p>注意与 {@code SysRoleController} 里那个"授权候选菜单树"的区别:
 * 这里是全量菜单树(含平台专用菜单),那个是**按租户套餐过滤后的**候选集(架构文档 5.2.1)。
 */
@RestController
@RequestMapping("/system/menus")
public class SysMenuController {

    private final MenuService menuService;

    public SysMenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping("/tree")
    @SaCheckPermission("system:menu:list")
    public ApiResponse<List<MenuTreeNode>> tree(@RequestParam(required = false) Integer status,
                                                @RequestParam(required = false) Integer menuType) {
        return ApiResponse.ok(menuService.tree(status, menuType));
    }

    @AuditLog(module = "菜单管理", permCode = "system:menu:create")
    @PostMapping
    @SaCheckPermission("system:menu:create")
    public ApiResponse<Long> create(@Valid @RequestBody MenuSaveRequest request) {
        return ApiResponse.ok(menuService.create(request));
    }

    @AuditLog(module = "菜单管理", permCode = "system:menu:update")
    @PutMapping("/{menuId}")
    @SaCheckPermission("system:menu:update")
    public ApiResponse<Void> update(@PathVariable Long menuId, @Valid @RequestBody MenuSaveRequest request) {
        menuService.update(menuId, request);
        return ApiResponse.ok();
    }

    /**
     * 删除菜单:被套餐/角色引用时会被级联清理,并对所有租户失效权限缓存(5.6、5.5)。
     */
    @AuditLog(module = "菜单管理", permCode = "system:menu:delete")
    @DeleteMapping("/{menuId}")
    @SaCheckPermission("system:menu:delete")
    public ApiResponse<Void> delete(@PathVariable Long menuId) {
        menuService.delete(menuId);
        return ApiResponse.ok();
    }
}
