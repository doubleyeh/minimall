package com.minimall.sys.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 保存租户微信支付配置。
 *
 * <p>密钥类字段为空表示"保持原值",所以更新时不用重填。创建时服务层会要求必填。
 *
 * @param tenantId           创建时必填;更新时以路径参数为准,忽略此字段
 * @param payMode            direct-普通商户 partner-服务商
 * @param loginAppSource     登录 code2Session 用哪套小程序凭据:app(默认)或 sub
 */
public record WxPayConfigSaveRequest(
        Long tenantId,
        @NotBlank(message = "支付模式不能为空") String payMode,
        @NotBlank(message = "商户号不能为空") String mchId,
        String subMchId,
        @NotBlank(message = "小程序 appId 不能为空") String appId,
        String appSecret,
        String subAppId,
        String subAppSecret,
        String loginAppSource,
        String apiV3Key,
        @NotBlank(message = "商户证书序列号不能为空") String merchantSerialNo,
        String merchantPrivateKey,
        String platformSerialNo,
        String platformPublicKey,
        Integer status,
        String remark) {
}
