package com.minimall.mall.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.common.ApiResponse;
import com.minimall.mall.api.dto.SalesStatReport;
import com.minimall.mall.service.SalesStatService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 销售统计(商城设计文档 3.4)。
 *
 * <p>日期区间是**必填**:不传就统计"全部",那是个既慢又没人看的数字,而且订单量上来之后
 * 一次全表聚合会拖住库。让调用方明确说要哪一段。
 */
@RestController
@RequestMapping("/mall/admin/sales-stats")
public class MallSalesStatController {

    private final SalesStatService salesStatService;

    public MallSalesStatController(SalesStatService salesStatService) {
        this.salesStatService = salesStatService;
    }

    @GetMapping
    @SaCheckPermission("mall:stat:list")
    public ApiResponse<SalesStatReport> report(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ApiResponse.ok(salesStatService.report(startTime, endTime));
    }
}
