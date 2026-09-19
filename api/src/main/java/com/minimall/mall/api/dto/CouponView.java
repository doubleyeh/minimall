package com.minimall.mall.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券(管理端列表)。
 *
 * <p>带 {@code receivedCount}:运营最关心的是"发出去了多少张",列表里直接用,
 * 不必再去查领取记录表。
 */
public record CouponView(
        Long id,
        String couponName,
        Integer couponType,
        BigDecimal discountAmount,
        BigDecimal discountRate,
        BigDecimal minOrderAmount,
        Integer totalCount,
        Integer receivedCount,
        Integer perCustomerLimit,
        LocalDateTime validStartTime,
        LocalDateTime validEndTime,
        Integer status) {
}
