package com.minimall.mall.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.mall.api.dto.AdminOrderQuery;
import com.minimall.mall.api.dto.AdminOrderView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditLog;
import com.minimall.infra.export.CsvExporter;
import com.minimall.mall.service.OrderAdminService;
import com.minimall.mall.service.support.AdminOrderCsv;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家管理端 - 订单(商城设计文档 3.4)。
 */
@RestController
@RequestMapping("/mall/admin/orders")
public class MallOrderAdminController {

    /** 导出上限。超过就报错让调用方缩小时间范围,不静默截断(见 service 的说明)。 */
    private static final int EXPORT_MAX_ROWS = 10000;

    private final OrderAdminService orderAdminService;

    public MallOrderAdminController(OrderAdminService orderAdminService) {
        this.orderAdminService = orderAdminService;
    }

    @GetMapping
    @SaCheckPermission("mall:order:list")
    public ApiResponse<PageResult<AdminOrderView>> page(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endTime,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(orderAdminService.page(
                new AdminOrderQuery(orderNo, status, startTime, endTime), pageNo, pageSize));
    }

    /**
     * 导出当前筛选条件下的订单(CSV)。
     *
     * <p>权限码复用查询那一个:导出就是把"能看到的"读出去,单独开一个码只会变成
     * "同一个页面里两个权限点各自维护"。真正需要收的是"谁能看订单"。
     *
     * <p>响应体是文件字节,不套统一响应体(套了 Excel 打开就是一堆 JSON)。
     */
    @GetMapping("/export")
    @SaCheckPermission("mall:order:list")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endTime) {
        List<AdminOrderView> rows = orderAdminService.listForExport(
                new AdminOrderQuery(orderNo, status, startTime, endTime), EXPORT_MAX_ROWS);
        byte[] csv = CsvExporter.toCsv(AdminOrderCsv.HEADERS, rows.stream().map(AdminOrderCsv::cells).toList());
        return ResponseEntity.ok()
                // 文件名用纯 ASCII:中文文件名要按 RFC 5987 编码,各家浏览器行为还不一致,没必要
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"orders-" + LocalDate.now() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
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
