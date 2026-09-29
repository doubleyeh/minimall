package com.minimall.mall.service;

import com.minimall.mall.api.dto.StockWarnReport;

/**
 * 库存预警(商城设计文档 3.2)。
 *
 * <p>阈值走字典 {@code stock_warn_threshold},判据是**可售库存**({@code stock - lockedStock})。
 */
public interface StockWarnService {

    StockWarnReport page(int pageNo, int pageSize);
}
