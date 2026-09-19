package com.minimall.api.mall.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 小程序端优惠券视图(可领取列表与"我的券"共用)。
 *
 * <p>{@code recordId} 只在"我的券"里有值:领券列表还没有领取记录。
 * {@code statusText} 由服务端给中文文案 —— 端上三个页面(领券/我的券/下单选券)
 * 都要展示它,各自算一遍规则迟早会出现"同一张券在两个页面状态不一致"。
 */
public record ClientCouponView(
        Long couponId,
        /** 领取记录 ID;领券列表为 null,我的券为领取记录的主键(下单时用它) */
        Long recordId,
        String couponName,
        Integer couponType,
        BigDecimal discountAmount,
        BigDecimal discountRate,
        BigDecimal minOrderAmount,
        LocalDateTime validEndTime,
        /** 领取记录状态:1-未使用 2-已使用 3-已过期;领券列表为 null */
        Integer status,
        String statusText) {
}
