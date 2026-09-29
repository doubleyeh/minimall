package com.minimall.mall.api.dto;

import com.minimall.common.PageResult;

/**
 * 库存预警结果:预警行 + 本次用的阈值。
 *
 * <p>把阈值一起返回,是因为"为什么这个规格在列表里"必须能在页面上自证 ——
 * 阈值在字典里,页面自己去读一次字典就是两处口径。
 */
public record StockWarnReport(int threshold, PageResult<StockWarnView> page) {
}
