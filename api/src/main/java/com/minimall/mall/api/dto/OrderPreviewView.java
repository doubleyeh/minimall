package com.minimall.mall.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 结算试算结果。
 *
 * <p>金额字段与 {@link OrderCreateResponse} 一一对应,端上直接照着渲染金额明细;
 * 下单时的实际金额由服务端用同一段编排重算,两者逐分一致。
 */
public record OrderPreviewView(
        List<Item> items,
        BigDecimal goodsAmount,
        BigDecimal promotionDiscountAmount,
        BigDecimal couponDiscountAmount,
        /** 积分抵现金额 */
        BigDecimal pointsDiscountAmount,
        BigDecimal freightAmount,
        BigDecimal payAmount,
        /** 本次试算实际用掉的积分(受上限与可用积分约束) */
        Integer pointsUsed,
        /** 本单最多能用多少积分 —— 端上据此限制输入框 */
        Integer maxRedeemPoints,
        /** 客户当前可用积分(已剔除过期批次) */
        Integer customerPoints,
        /** 是否还没选收货地址(此时运费按 0 计,金额不是最终值) */
        boolean needAddress) {

    public record Item(
            Long skuId,
            Long goodsId,
            String goodsName,
            String skuName,
            String goodsImage,
            BigDecimal price,
            Integer quantity,
            BigDecimal totalAmount,
            Integer availableStock) {
    }
}
