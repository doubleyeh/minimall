package com.minimall.mall.infra.pay;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信支付的全局配置(商城设计文档 3.1)。每租户的商户号/密钥在 {@code sys_wx_pay_config} 里。
 *
 * @param notifyBaseUrl          回调地址前缀,必须是公网 HTTPS;租户级只拼 {@code /pay/callback/wx/{tenantCode}}
 * @param connectTimeoutSeconds  连接超时(秒)
 * @param readTimeoutSeconds     读超时(秒)
 * @param configCacheSeconds     租户支付配置的缓存 TTL(秒)
 * @param configCacheMissSeconds "该租户未配置"的负缓存 TTL(秒),用来挡穿透
 */
@ConfigurationProperties(prefix = "minimall.mall.pay")
public record WxPayProperties(
        String notifyBaseUrl,
        Integer connectTimeoutSeconds,
        Integer readTimeoutSeconds,
        Integer configCacheSeconds,
        Integer configCacheMissSeconds) {

    public int connectTimeoutSecondsOrDefault() {
        return connectTimeoutSeconds == null || connectTimeoutSeconds <= 0 ? 3 : connectTimeoutSeconds;
    }

    public int readTimeoutSecondsOrDefault() {
        return readTimeoutSeconds == null || readTimeoutSeconds <= 0 ? 10 : readTimeoutSeconds;
    }

    public int configCacheSecondsOrDefault() {
        return configCacheSeconds == null || configCacheSeconds <= 0 ? 600 : configCacheSeconds;
    }

    public int configCacheMissSecondsOrDefault() {
        return configCacheMissSeconds == null || configCacheMissSeconds <= 0 ? 60 : configCacheMissSeconds;
    }

    public boolean notifyBaseUrlConfigured() {
        return notifyBaseUrl != null && !notifyBaseUrl.isBlank();
    }

    /** 拼该租户的回调地址;{@code refund} 为真时是退款回调。 */
    public String notifyUrl(String tenantCode, boolean refund) {
        String base = notifyBaseUrl == null ? "" : notifyBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/pay/callback/wx/" + tenantCode + (refund ? "/refund" : "");
    }
}
