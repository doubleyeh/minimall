package com.minimall.mall.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.mall.api.dto.FreightTemplateSaveRequest;
import com.minimall.mall.api.dto.FreightTemplateView;
import com.minimall.common.ApiResponse;
import com.minimall.infra.audit.AuditLog;
import com.minimall.mall.service.FreightTemplateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家管理端 - 运费模板(商城设计文档 3.7)。
 */
@RestController
@RequestMapping("/mall/admin/freight-templates")
public class MallFreightTemplateController {

    private final FreightTemplateService freightTemplateService;

    public MallFreightTemplateController(FreightTemplateService freightTemplateService) {
        this.freightTemplateService = freightTemplateService;
    }

    @GetMapping
    @SaCheckPermission("mall:freight:list")
    public ApiResponse<List<FreightTemplateView>> list() {
        return ApiResponse.ok(freightTemplateService.list());
    }

    @GetMapping("/{templateId}")
    @SaCheckPermission("mall:freight:list")
    public ApiResponse<FreightTemplateView> detail(@PathVariable Long templateId) {
        return ApiResponse.ok(freightTemplateService.detail(templateId));
    }

    @AuditLog(module = "运费模板", permCode = "mall:freight:create")
    @PostMapping
    @SaCheckPermission("mall:freight:create")
    public ApiResponse<Long> create(@Valid @RequestBody FreightTemplateSaveRequest request) {
        return ApiResponse.ok(freightTemplateService.create(request));
    }

    @AuditLog(module = "运费模板", permCode = "mall:freight:update")
    @PutMapping("/{templateId}")
    @SaCheckPermission("mall:freight:update")
    public ApiResponse<Void> update(@PathVariable Long templateId,
                                    @Valid @RequestBody FreightTemplateSaveRequest request) {
        freightTemplateService.update(templateId, request);
        return ApiResponse.ok();
    }

    @AuditLog(module = "运费模板", permCode = "mall:freight:delete")
    @DeleteMapping("/{templateId}")
    @SaCheckPermission("mall:freight:delete")
    public ApiResponse<Void> delete(@PathVariable Long templateId) {
        freightTemplateService.delete(templateId);
        return ApiResponse.ok();
    }
}
