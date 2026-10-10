package com.minimall.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 可信代理名单:只有来自这些地址的 {@code X-Forwarded-For} 才会被采信。
 *
 * @param trustedProxies 可信代理 IP(支持 IPv4 CIDR,如 {@code 10.0.0.0/8});
 *                       为空表示任何来源都不采信 XFF
 */
@ConfigurationProperties(prefix = "minimall.security")
public record TrustedProxyProperties(List<String> trustedProxies) {

    public TrustedProxyProperties {
        trustedProxies = trustedProxies == null ? List.of() : List.copyOf(trustedProxies);
    }
}
