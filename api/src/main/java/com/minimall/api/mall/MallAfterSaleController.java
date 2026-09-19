package com.minimall.api.mall;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.api.mall.dto.AfterSaleView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditLog;
import com.minimall.mall.service.AfterSaleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 商家管理端 - 售后(商城设计文档 3.9)。
 *
 * <p>本期没有独立客服角色:客服介入与仲裁也走这里的接口,由商家或超管操作,
 * 但服务端记的操作人类型仍是"平台客服",将来接入独立角色时不需要改数据含义。
 */
@RestController
@RequestMapping("/mall/admin/after-sales")
public class MallAfterSaleController {

    private final AfterSaleService afterSaleService;

    public MallAfterSaleController(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    @GetMapping
    @SaCheckPermission("mall:after-sale:list")
    public ApiResponse<PageResult<AfterSaleView>> page(@RequestParam(required = false) Integer status,
                                                       @RequestParam(required = false) String afterSaleNo,
                                                       @RequestParam(defaultValue = "1") int pageNo,
                                                       @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(afterSaleService.page(status, afterSaleNo, pageNo, pageSize));
    }

    @GetMapping("/{afterSaleId}")
    @SaCheckPermission("mall:after-sale:list")
    public ApiResponse<AfterSaleView> detail(@PathVariable Long afterSaleId) {
        return ApiResponse.ok(afterSaleService.detail(afterSaleId));
    }

    /** 同意(可下调退款金额)。 */
    @AuditLog(module = "售后处理", permCode = "mall:after-sale:handle")
    @PostMapping("/{afterSaleId}/approve")
    @SaCheckPermission("mall:after-sale:handle")
    public ApiResponse<Void> approve(@PathVariable Long afterSaleId,
                                     @RequestBody(required = false) ApproveRequest request) {
        afterSaleService.approve(afterSaleId, request == null ? null : request.refundAmount());
        return ApiResponse.ok();
    }

    @AuditLog(module = "售后处理", permCode = "mall:after-sale:handle")
    @PostMapping("/{afterSaleId}/reject")
    @SaCheckPermission("mall:after-sale:handle")
    public ApiResponse<Void> reject(@PathVariable Long afterSaleId, @RequestBody RejectRequest request) {
        afterSaleService.reject(afterSaleId, request.reason());
        return ApiResponse.ok();
    }

    /** 确认收到退货。换货场景需要带新的 SKU 与重新发货的物流。 */
    @AuditLog(module = "售后处理", permCode = "mall:after-sale:handle")
    @PostMapping("/{afterSaleId}/confirm-receive")
    @SaCheckPermission("mall:after-sale:handle")
    public ApiResponse<Void> confirmReceive(@PathVariable Long afterSaleId,
                                            @RequestBody(required = false) ConfirmReceiveRequest request) {
        afterSaleService.confirmReturnReceived(afterSaleId,
                request == null ? null : request.newSkuId(),
                request == null ? null : request.logisticsCompany(),
                request == null ? null : request.logisticsNo());
        return ApiResponse.ok();
    }

    @AuditLog(module = "售后处理", permCode = "mall:after-sale:handle")
    @PostMapping("/{afterSaleId}/reject-receive")
    @SaCheckPermission("mall:after-sale:handle")
    public ApiResponse<Void> rejectReceive(@PathVariable Long afterSaleId, @RequestBody RejectRequest request) {
        afterSaleService.rejectReturn(afterSaleId, request.reason());
        return ApiResponse.ok();
    }

    /** 客服仲裁(本期由商家或超管操作)。 */
    @AuditLog(module = "售后处理", permCode = "mall:after-sale:handle")
    @PostMapping("/{afterSaleId}/arbitrate")
    @SaCheckPermission("mall:after-sale:handle")
    public ApiResponse<Void> arbitrate(@PathVariable Long afterSaleId, @RequestBody ArbitrateRequest request) {
        afterSaleService.arbitrate(afterSaleId, Boolean.TRUE.equals(request.pass()), request.remark());
        return ApiResponse.ok();
    }

    /** 同意请求。 */
    public record ApproveRequest(BigDecimal refundAmount) {
    }

    /** 拒绝请求。 */
    public record RejectRequest(String reason) {
    }

    /** 确认收货请求。 */
    public record ConfirmReceiveRequest(Long newSkuId, String logisticsCompany, String logisticsNo) {
    }

    /** 仲裁请求。 */
    public record ArbitrateRequest(Boolean pass, String remark) {
    }
}
