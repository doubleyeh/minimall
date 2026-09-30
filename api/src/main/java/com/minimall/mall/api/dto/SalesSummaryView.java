package com.minimall.mall.api.dto;

import java.math.BigDecimal;

/**
 * 销售汇总(管理端)。
 *
 * <p>{@code paidAmount} 按**下单时间**落区间、且已支付的订单实付额求和;{@code refundAmount} 按
 * **退款成功时间**落区间求和 —— 两者不是同一批订单,所以 {@code netAmount} 只看量级,
 * 不能当成严格对账数字(页面需要提示这一点)。
 */
public record SalesSummaryView(
        long orderCount,
        BigDecimal paidAmount,
        BigDecimal refundAmount,
        BigDecimal netAmount,
        BigDecimal avgOrderAmount) {
}
