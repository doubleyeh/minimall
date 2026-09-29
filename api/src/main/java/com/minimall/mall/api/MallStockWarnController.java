package com.minimall.mall.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.common.ApiResponse;
import com.minimall.mall.api.dto.StockWarnReport;
import com.minimall.mall.service.StockWarnService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存预警(商城设计文档 3.2)。
 *
 * <p>只读:预警是"把该处理的挑出来",改库存仍然走商品编辑 —— 在这里直接改库存
 * 会绕过 SKU 的并发保护(见 {@code MallSkuRepository} 的说明)。
 */
@RestController
@RequestMapping("/mall/admin/stock-warns")
public class MallStockWarnController {

    private final StockWarnService stockWarnService;

    public MallStockWarnController(StockWarnService stockWarnService) {
        this.stockWarnService = stockWarnService;
    }

    @GetMapping
    @SaCheckPermission("mall:stock:list")
    public ApiResponse<StockWarnReport> page(@RequestParam(defaultValue = "1") int pageNo,
                                             @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(stockWarnService.page(pageNo, pageSize));
    }
}
