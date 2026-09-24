package com.minimall.sys.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditLog;
import com.minimall.sys.api.dto.WxPayConfigSaveRequest;
import com.minimall.sys.api.dto.WxPayConfigView;
import com.minimall.sys.service.WxPayConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信支付配置管理接口(平台级,只有平台超管可用)。
 *
 * <p>查询不回显任何密钥,只回"是否已配置"。
 */
@RestController
@RequestMapping("/system/wx-pay-configs")
public class SysWxPayConfigController {

    private final WxPayConfigService wxPayConfigService;

    public SysWxPayConfigController(WxPayConfigService wxPayConfigService) {
        this.wxPayConfigService = wxPayConfigService;
    }

    @GetMapping
    @SaCheckPermission("system:wxpay:list")
    public ApiResponse<PageResult<WxPayConfigView>> page(@RequestParam(required = false) String tenantCode,
                                                         @RequestParam(required = false) Integer status,
                                                         @RequestParam(defaultValue = "1") int pageNo,
                                                         @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(wxPayConfigService.page(tenantCode, status, pageNo, pageSize));
    }

    @GetMapping("/{tenantId}")
    @SaCheckPermission("system:wxpay:list")
    public ApiResponse<WxPayConfigView> detail(@PathVariable Long tenantId) {
        return ApiResponse.ok(wxPayConfigService.get(tenantId));
    }

    @AuditLog(module = "微信支付配置", permCode = "system:wxpay:update")
    @PostMapping
    @SaCheckPermission("system:wxpay:update")
    public ApiResponse<Long> create(@Valid @RequestBody WxPayConfigSaveRequest request) {
        return ApiResponse.ok(wxPayConfigService.create(request));
    }

    @AuditLog(module = "微信支付配置", permCode = "system:wxpay:update")
    @PutMapping("/{tenantId}")
    @SaCheckPermission("system:wxpay:update")
    public ApiResponse<Void> update(@PathVariable Long tenantId,
                                    @Valid @RequestBody WxPayConfigSaveRequest request) {
        wxPayConfigService.update(tenantId, request);
        return ApiResponse.ok();
    }

    @AuditLog(module = "微信支付配置", permCode = "system:wxpay:update")
    @PutMapping("/{tenantId}/status")
    @SaCheckPermission("system:wxpay:update")
    public ApiResponse<Void> updateStatus(@PathVariable Long tenantId, @RequestParam Integer status) {
        wxPayConfigService.updateStatus(tenantId, status);
        return ApiResponse.ok();
    }
}
