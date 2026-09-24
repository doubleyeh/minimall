package com.minimall.sys.api.dto;

import java.time.LocalDateTime;

/**
 * 租户微信支付配置的查询视图。
 *
 * <p>密钥类字段只回"是否已配置",**不回显任何密钥内容** —— 后台页面只需要知道要不要重填。
 *
 * @param configured 该租户是否已有配置;没有配置时后面的支付字段全为 null
 */
public record WxPayConfigView(
        Long tenantId,
        String tenantCode,
        String tenantName,
        boolean configured,
        String payMode,
        String mchId,
        String subMchId,
        String appId,
        String subAppId,
        String loginAppSource,
        String merchantSerialNo,
        String platformSerialNo,
        boolean appSecretConfigured,
        boolean subAppSecretConfigured,
        boolean apiV3KeyConfigured,
        boolean merchantPrivateKeyConfigured,
        boolean platformPublicKeyConfigured,
        Integer status,
        String remark,
        LocalDateTime updateTime) {
}
