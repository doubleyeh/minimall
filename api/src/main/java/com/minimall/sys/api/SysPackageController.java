package com.minimall.sys.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.sys.api.dto.MenuTreeNode;
import com.minimall.sys.api.dto.PackageMenuSaveRequest;
import com.minimall.sys.api.dto.PackageSaveRequest;
import com.minimall.sys.api.dto.PackageView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.sys.service.PackageService;
import jakarta.validation.Valid;
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
 * 套餐管理接口(平台级,只有平台超管可用)。
 */
@RestController
@RequestMapping("/system/packages")
public class SysPackageController {

    private final PackageService packageService;

    public SysPackageController(PackageService packageService) {
        this.packageService = packageService;
    }

    @GetMapping
    @SaCheckPermission("system:package:list")
    public ApiResponse<PageResult<PackageView>> page(@RequestParam(required = false) String packageName,
                                                     @RequestParam(required = false) Integer status,
                                                     @RequestParam(defaultValue = "1") int pageNo,
                                                     @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(packageService.page(packageName, status, pageNo, pageSize));
    }

    @AuditLog(module = "套餐管理", permCode = "system:package:create")
    @PostMapping
    @SaCheckPermission("system:package:create")
    public ApiResponse<Long> create(@Valid @RequestBody PackageSaveRequest request) {
        return ApiResponse.ok(packageService.create(request));
    }

    @AuditLog(module = "套餐管理", permCode = "system:package:update")
    @PutMapping("/{packageId}")
    @SaCheckPermission("system:package:update")
    public ApiResponse<Void> update(@PathVariable Long packageId, @Valid @RequestBody PackageSaveRequest request) {
        packageService.update(packageId, request);
        return ApiResponse.ok();
    }

    /** 套餐不做物理删除,只能禁用(5.6)。 */
    @AuditLog(module = "套餐管理", permCode = "system:package:update")
    @PutMapping("/{packageId}/disable")
    @SaCheckPermission("system:package:update")
    public ApiResponse<Void> disable(@PathVariable Long packageId) {
        packageService.disable(packageId);
        return ApiResponse.ok();
    }

    /** 套餐可选菜单树(全部非平台专用菜单)。 */
    @GetMapping("/{packageId}/menus/grantable")
    @SaCheckPermission("system:package:update")
    public ApiResponse<List<MenuTreeNode>> grantableMenus(@PathVariable Long packageId) {
        return ApiResponse.ok(packageService.grantableMenuTree(packageId));
    }

    /** 套餐已包含的菜单 ID(编辑界面回显)。 */
    @GetMapping("/{packageId}/menus")
    @SaCheckPermission("system:package:update")
    public ApiResponse<List<Long>> menus(@PathVariable Long packageId) {
        return ApiResponse.ok(packageService.menuIds(packageId));
    }

    /**
     * 保存套餐菜单:**下一次权限读取即生效**。套餐在权限计算时是上限,不回写租户角色,
     * 所以这里没有"逐租户同步",也不存在"保存成功但没同步"的中间状态。
     */
    @AuditLog(module = "套餐管理", permCode = "system:package:update")
    @PutMapping("/{packageId}/menus")
    @SaCheckPermission("system:package:update")
    public ApiResponse<Void> saveMenus(@PathVariable Long packageId,
                                       @Valid @RequestBody PackageMenuSaveRequest request) {
        packageService.saveMenus(packageId, request);
        return ApiResponse.ok();
    }

}
