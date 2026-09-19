package com.minimall.mall.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.mall.api.dto.AdminOrderView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditLog;
import com.minimall.mall.service.OrderAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家管理端 - 订单(商城设计文档 3.4)。
 */
@RestController
@RequestMapping("/mall/admin/orders")
public class MallOrderAdminController {

    private final OrderAdminService orderAdminService;

    public MallOrderAdminController(OrderAdminService orderAdminService) {
        this.orderAdminService = orderAdminService;
    }

    @GetMapping
    @SaCheckPermission("mall:order:list")
    public ApiResponse<PageResult<AdminOrderView>> page(@RequestParam(required = false) String orderNo,
                                                        @RequestParam(required = false) Integer status,
                                                        @RequestParam(defaultValue = "1") int pageNo,
                                                        @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(orderAdminService.page(orderNo, status, pageNo, pageSize));
    }

    @GetMapping("/{orderId}")
    @SaCheckPermission("mall:order:list")
    public ApiResponse<AdminOrderView> detail(@PathVariable Long orderId) {
        return ApiResponse.ok(orderAdminService.detail(orderId));
    }

    @AuditLog(module = "商城订单", permCode = "mall:order:ship")
    @PostMapping("/{orderId}/ship")
    @SaCheckPermission("mall:order:ship")
    public ApiResponse<Void> ship(@PathVariable Long orderId, @RequestBody ShipRequest request) {
        orderAdminService.ship(orderId, request.logisticsCompany(), request.logisticsNo());
        return ApiResponse.ok();
    }

    @AuditLog(module = "商城订单", permCode = "mall:order:cancel")
    @PostMapping("/{orderId}/cancel")
    @SaCheckPermission("mall:order:cancel")
    public ApiResponse<Void> cancel(@PathVariable Long orderId, @RequestBody(required = false) CancelRequest request) {
        orderAdminService.cancel(orderId, request == null ? null : request.reason());
        return ApiResponse.ok();
    }

    /** 发货请求。 */
    public record ShipRequest(String logisticsCompany, String logisticsNo) {
    }

    /** 取消请求。 */
    public record CancelRequest(String reason) {
    }
}
