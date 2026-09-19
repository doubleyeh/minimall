package com.minimall.sys.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import com.minimall.sys.api.dto.TenantPackageChangeRequest;
import com.minimall.sys.api.dto.TenantView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.sys.service.TenantService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.minimall.infra.audit.AuditLog;

/**
 * 租户管理接口(架构文档 4.7、4.8、4.11)。
 *
 * <p>平台级接口:能调它的角色必须持有对应的 perm_code。这些菜单在 sys_menu 上标 {@code is_platform = 1},
 * 且不允许进入任何套餐,所以普通租户在数据库层面就取不到这些权限(4.10 的第一层防线)。
 */
@RestController
@RequestMapping("/system/tenants")
public class SysTenantController {

    private final TenantService tenantService;

    public SysTenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @GetMapping
    @SaCheckPermission("system:tenant:list")
    public ApiResponse<PageResult<TenantView>> page(@RequestParam(required = false) String tenantCode,
                                                    @RequestParam(required = false) Integer status,
                                                    @RequestParam(defaultValue = "1") int pageNo,
                                                    @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(tenantService.page(tenantCode, status, pageNo, pageSize));
    }

    /**
     * 创建租户。响应里的 initialPassword 只返回这一次(架构文档 7.1.2)。
     */
    @AuditLog(module = "租户管理", permCode = "system:tenant:create")
    @PostMapping
    @SaCheckPermission("system:tenant:create")
    public ApiResponse<TenantCreateResponse> create(@Valid @RequestBody TenantCreateRequest request) {
        return ApiResponse.ok(tenantService.create(request));
    }

    /**
     * 变更套餐:升级只影响默认管理员角色,降级对该租户全部角色立即收回(4.8)。
     */
    @AuditLog(module = "租户管理", permCode = "system:tenant:package")
    @PutMapping("/{tenantId}/package")
    @SaCheckPermission("system:tenant:package")
    public ApiResponse<Void> changePackage(@PathVariable Long tenantId,
                                           @Valid @RequestBody TenantPackageChangeRequest request) {
        tenantService.changePackage(tenantId, request);
        return ApiResponse.ok();
    }

    /**
     * 启用/禁用:禁用会让该租户在线会话在下一次请求失效(4.11)。
     */
    @AuditLog(module = "租户管理", permCode = "system:tenant:status")
    @PutMapping("/{tenantId}/status")
    @SaCheckPermission("system:tenant:status")
    public ApiResponse<Void> changeStatus(@PathVariable Long tenantId,
                                         @RequestParam int status) {
        tenantService.changeStatus(tenantId, status);
        return ApiResponse.ok();
    }
}
