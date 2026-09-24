package com.minimall.mall.infra.pay;

import java.math.BigDecimal;

/**
 * JSAPI 统一下单的入参。
 *
 * @param amount      应付金额(元);微信报文里会转成整数分
 * @param openid      付款人的小程序 openid
 * @param tenantCode  回调地址要带租户编码 —— 微信回调体是密文,不知道租户就不知道用哪把密钥解密
 */
public record WxPayOrderCommand(Long tenantId, String tenantCode, String outTradeNo, BigDecimal amount,
                                String openid, String description) {
}
