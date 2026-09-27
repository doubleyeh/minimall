package com.minimall.sys.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import com.minimall.sys.api.dto.TenantExpireTimeRequest;
import com.minimall.sys.api.dto.TenantPackageChangeRequest;
import com.minimall.sys.api.dto.TenantView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.sys.service.TenantService;
import com.minimall.sys.service.support.TenantDataExporter;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
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
    private final TenantDataExporter tenantDataExporter;

    public SysTenantController(TenantService tenantService, TenantDataExporter tenantDataExporter) {
        this.tenantService = tenantService;
        this.tenantDataExporter = tenantDataExporter;
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

    /**
     * 注销租户(4.11):必须先禁用。保留期内数据一行不动、可以取消注销,到期由任务物理删除。
     */
    @AuditLog(module = "租户管理", permCode = "system:tenant:close")
    @PutMapping("/{tenantId}/close")
    @SaCheckPermission("system:tenant:close")
    public ApiResponse<Void> close(@PathVariable Long tenantId) {
        tenantService.close(tenantId);
        return ApiResponse.ok();
    }

    /** 取消注销:保留期内有效。 */
    @AuditLog(module = "租户管理", permCode = "system:tenant:close")
    @PutMapping("/{tenantId}/close/cancel")
    @SaCheckPermission("system:tenant:close")
    public ApiResponse<Void> cancelClose(@PathVariable Long tenantId) {
        tenantService.cancelClose(tenantId);
        return ApiResponse.ok();
    }

    /**
     * 导出该租户的全部数据(ZIP,每张表一个 CSV) —— 物理删除前留一份存档的唯一手段。
     *
     * <p>权限码与"注销"共用:能删就能导。反过来单独给个"导出"码,等于让"只能看不能删"的角色
     * 也能把整租户的数据拖走。
     */
    @AuditLog(module = "租户管理", permCode = "system:tenant:close")
    @GetMapping("/{tenantId}/data-export")
    @SaCheckPermission("system:tenant:close")
    public ResponseEntity<byte[]> exportData(@PathVariable Long tenantId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"tenant-" + tenantId + "-" + LocalDate.now() + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(tenantDataExporter.export(tenantId));
    }

    /**
     * 改有效期:与启停同样惰性生效(4.11);传空表示改为不过期。
     */
    @AuditLog(module = "租户管理", permCode = "system:tenant:expire")
    @PutMapping("/{tenantId}/expire-time")
    @SaCheckPermission("system:tenant:expire")
    public ApiResponse<Void> changeExpireTime(@PathVariable Long tenantId,
                                              @RequestBody TenantExpireTimeRequest request) {
        tenantService.changeExpireTime(tenantId, request.expireTime());
        return ApiResponse.ok();
    }
}
