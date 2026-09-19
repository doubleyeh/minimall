package com.minimall.mall.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.mall.api.dto.PromotionSaveRequest;
import com.minimall.mall.api.dto.PromotionView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditLog;
import com.minimall.mall.service.PromotionService;
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
 * 商家管理端 - 满减活动(商城设计文档 3.5)。
 */
@RestController
@RequestMapping("/mall/admin/promotions")
public class MallPromotionController {

    private final PromotionService promotionService;

    public MallPromotionController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @GetMapping
    @SaCheckPermission("mall:promotion:list")
    public ApiResponse<PageResult<PromotionView>> page(@RequestParam(required = false) String activityName,
                                                       @RequestParam(required = false) Integer status,
                                                       @RequestParam(defaultValue = "1") int pageNo,
                                                       @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(promotionService.page(activityName, status, pageNo, pageSize));
    }

    @AuditLog(module = "满减活动", permCode = "mall:promotion:create")
    @PostMapping
    @SaCheckPermission("mall:promotion:create")
    public ApiResponse<Long> create(@Valid @RequestBody PromotionSaveRequest request) {
        return ApiResponse.ok(promotionService.create(request));
    }

    @AuditLog(module = "满减活动", permCode = "mall:promotion:update")
    @PutMapping("/{activityId}")
    @SaCheckPermission("mall:promotion:update")
    public ApiResponse<Void> update(@PathVariable Long activityId,
                                    @Valid @RequestBody PromotionSaveRequest request) {
        promotionService.update(activityId, request);
        return ApiResponse.ok();
    }

    @AuditLog(module = "满减活动", permCode = "mall:promotion:update")
    @PutMapping("/{activityId}/status")
    @SaCheckPermission("mall:promotion:update")
    public ApiResponse<Void> changeStatus(@PathVariable Long activityId, @RequestParam Integer status) {
        promotionService.changeStatus(activityId, status);
        return ApiResponse.ok();
    }
}
