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
     * 保存套餐菜单:保存后**立即**对所有绑定该套餐的租户生效(4.8.1)。
     * 租户多时这是一个耗时操作,接口同步返回"已受理并处理完成/部分失败"的结果由实现决定,
     * 但语义上必须是"保存完成 = 已同步完成或已记录失败租户",不能出现"保存成功但没同步"的状态。
     */
    @AuditLog(module = "套餐管理", permCode = "system:package:update")
    @PutMapping("/{packageId}/menus")
    @SaCheckPermission("system:package:update")
    public ApiResponse<Void> saveMenus(@PathVariable Long packageId,
                                       @Valid @RequestBody PackageMenuSaveRequest request) {
        packageService.saveMenus(packageId, request);
        return ApiResponse.ok();
    }

    /** 运维出口:重跑同步,让停在"未同步"状态的租户收敛(4.8.1)。 */
    @AuditLog(module = "套餐管理", permCode = "system:package:update")
    @PostMapping("/{packageId}/resync")
    @SaCheckPermission("system:package:update")
    public ApiResponse<Void> resync(@PathVariable Long packageId) {
        packageService.resync(packageId);
        return ApiResponse.ok();
    }
}
