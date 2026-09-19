package com.minimall.mall.api.dto;

import java.math.BigDecimal;

/**
 * 下单结果(端上据此拉起微信支付)。
 *
 * <p>金额字段全部返回:端上要展示"商品 XX + 运费 X - 优惠 Y = 实付 Z"的明细,
 * 只返回实付金额会让用户怀疑优惠没生效。
 */
public record OrderCreateResponse(
        Long orderId,
        String orderNo,
        BigDecimal goodsAmount,
        BigDecimal freightAmount,
        BigDecimal promotionDiscountAmount,
        BigDecimal couponDiscountAmount,
        BigDecimal payAmount,
        /** 支付所需参数(由 {@link PayParams} 承载;未接通支付渠道时可能为 null) */
        PayParams payParams) {

    /**
     * 拉起支付所需参数。
     *
     * <p>字段与微信小程序 {@code wx.requestPayment} 一致;真实接入时由统一下单接口返回,
     * 未配置支付渠道的本地环境里由模拟实现填充(见 {@code WxPayClient})。
     */
    public record PayParams(
            String timeStamp,
            String nonceStr,
            String packageValue,
            String signType,
            String paySign) {
    }
}
