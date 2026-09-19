package com.minimall.api.mall;

import com.minimall.api.mall.dto.AfterSaleApplyRequest;
import com.minimall.api.mall.dto.AfterSaleView;
import com.minimall.common.ApiResponse;
import com.minimall.mall.service.AfterSaleService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端 - 售后(商城设计文档 3.9)。
 */
@RestController
@RequestMapping("/mall/api/after-sales")
public class ClientAfterSaleController {

    private final AfterSaleService afterSaleService;

    public ClientAfterSaleController(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    @PostMapping
    public ApiResponse<Long> apply(@Valid @RequestBody AfterSaleApplyRequest request) {
        return ApiResponse.ok(afterSaleService.apply(request));
    }

    @GetMapping
    public ApiResponse<List<AfterSaleView>> mine(@RequestParam(required = false) Integer status) {
        return ApiResponse.ok(afterSaleService.mine(status));
    }

    @GetMapping("/{afterSaleId}")
    public ApiResponse<AfterSaleView> detail(@PathVariable Long afterSaleId) {
        return ApiResponse.ok(afterSaleService.detailForBuyer(afterSaleId));
    }

    /** 撤销申请(被拒绝后可撤销,重新走流程)。 */
    @PostMapping("/{afterSaleId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long afterSaleId) {
        afterSaleService.cancelByBuyer(afterSaleId);
        return ApiResponse.ok();
    }

    /** 提交退货物流。 */
    @PostMapping("/{afterSaleId}/return-logistics")
    public ApiResponse<Void> submitReturnLogistics(@PathVariable Long afterSaleId,
                                                   @RequestBody LogisticsRequest request) {
        afterSaleService.submitReturnLogistics(afterSaleId, request.company(), request.no());
        return ApiResponse.ok();
    }

    /** 申请客服介入。 */
    @PostMapping("/{afterSaleId}/arbitration")
    public ApiResponse<Void> requestArbitration(@PathVariable Long afterSaleId) {
        afterSaleService.requestArbitration(afterSaleId);
        return ApiResponse.ok();
    }

    /** 物流信息请求体。 */
    public record LogisticsRequest(String company, String no) {
    }
}
