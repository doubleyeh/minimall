package com.minimall.mall.infra.pay;

import java.math.BigDecimal;

/**
 * 退款的入参。
 *
 * @param transactionId 微信支付单号;有它就用它(唯一、不依赖商户单号语义)
 * @param outTradeNo    {@code transactionId} 取不到时的回退;direct 模式下必填
 * @param totalAmount   原订单支付总额(元),微信要求报原单金额,不能只报本次退款
 */
public record WxPayRefundCommand(Long tenantId, String tenantCode, String outTradeNo, String transactionId,
                                 String outRefundNo, BigDecimal refundAmount, BigDecimal totalAmount,
                                 String reason) {
}
