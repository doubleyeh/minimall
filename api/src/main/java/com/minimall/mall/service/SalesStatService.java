package com.minimall.mall.service;

import com.minimall.mall.api.dto.SalesStatReport;

import java.time.LocalDateTime;

/**
 * 销售统计(商城设计文档 3.4)。
 *
 * <p>两个口径必须分清,见 {@link com.minimall.mall.api.dto.SalesSummaryView}。
 */
public interface SalesStatService {

    /**
     * @param startTime 下单时间区间起点(含)
     * @param endTime   下单时间区间终点(含)
     */
    SalesStatReport report(LocalDateTime startTime, LocalDateTime endTime);
}
