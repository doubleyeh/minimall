package com.minimall.mall.infra.pay;

/**
 * 一个租户的微信支付凭据(已解密)。
 *
 * <p>{@link #toString()} 被覆写为不输出密钥:record 默认会打印全部字段,一旦被日志或异常带出去
 * 就等于把商户私钥写进了日志。
 */
public record WxPayCredentials(
        Long tenantId,
        String payMode,
        String mchId,
        String subMchId,
        String appId,
        String appSecret,
        String subAppId,
        String subAppSecret,
        String loginAppSource,
        String apiV3Key,
        String merchantSerialNo,
        String merchantPrivateKey,
        String platformSerialNo,
        String platformPublicKey) {

    public static final String MODE_PARTNER = "partner";
    public static final String LOGIN_SOURCE_SUB = "sub";

    public boolean partner() {
        return MODE_PARTNER.equals(payMode);
    }

    /** 登录 code2Session 的 appId:按配置决定取 app 还是 sub 那套。 */
    public String loginAppId() {
        return LOGIN_SOURCE_SUB.equals(loginAppSource) ? subAppId : appId;
    }

    public String loginAppSecret() {
        return LOGIN_SOURCE_SUB.equals(loginAppSource) ? subAppSecret : appSecret;
    }

    /** 下单报文的 appId:partner 用 sp_appid,其余用 app_id。 */
    public String orderAppId() {
        return appId;
    }

    /** 下单报文里 openid 所属的 appId:partner 且配了 sub_app_id 时是特约商户小程序。 */
    public String payerAppId() {
        return partner() && subAppId != null && !subAppId.isBlank() ? subAppId : appId;
    }

    @Override
    public String toString() {
        return "WxPayCredentials[tenantId=" + tenantId + ", payMode=" + payMode
                + ", mchId=" + mchId + ", subMchId=" + subMchId
                + ", appId=" + appId + ", subAppId=" + subAppId + ", 密钥已省略]";
    }
}
