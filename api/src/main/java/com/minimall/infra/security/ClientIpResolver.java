package com.minimall.infra.security;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 解析请求的真实客户端 IP(架构文档 7.1.1、9.8)。
 *
 * <p><b>不采信客户端自己带来的 {@code X-Forwarded-For}</b>:只有 TCP 对端({@code getRemoteAddr()})
 * 命中可信代理名单时才解析 XFF,否则一律用对端地址。理由:IP 同时用于登录限流的计数键和审计日志,
 * 让客户端能自己决定它,限流等于没做、审计记录也不可信。
 *
 * <p>采信时的取法:从 XFF 最右侧往左跳过可信代理,取第一个不可信的地址。我们的 nginx 约定是
 * {@code proxy_set_header X-Forwarded-For $remote_addr}(覆盖写而不是追加),此时 XFF 只有一个值。
 */
@Component
public class ClientIpResolver {

    private final List<String> trustedProxies;

    public ClientIpResolver(TrustedProxyProperties properties) {
        this.trustedProxies = properties.trustedProxies();
    }

    /**
     * @param remoteAddr TCP 对端地址,不为 null
     * @param forwarded  {@code X-Forwarded-For} 请求头原文,可为 null
     */
    public String resolve(String remoteAddr, String forwarded) {
        if (!isTrusted(remoteAddr)) {
            // 直连或来源不可信:请求头一律不算数
            return remoteAddr;
        }
        if (forwarded == null || forwarded.isBlank()) {
            return remoteAddr;
        }
        String[] hops = forwarded.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String candidate = hops[i].trim();
            if (candidate.isEmpty() || isTrusted(candidate)) {
                continue;
            }
            return candidate;
        }
        return remoteAddr;
    }

    private boolean isTrusted(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String ip = address.trim();
        for (String rule : trustedProxies) {
            if (matches(ip, rule)) {
                return true;
            }
        }
        return false;
    }

    /** 精确匹配,或 IPv4 CIDR(如 {@code 10.0.0.0/8});IPv6 只做精确匹配。 */
    private boolean matches(String ip, String rule) {
        int slash = rule.indexOf('/');
        if (slash < 0) {
            return ip.equals(rule);
        }
        if (ip.indexOf(':') >= 0) {
            return false;
        }
        int prefix = Integer.parseInt(rule.substring(slash + 1).trim());
        int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
        return (toIpv4Int(ip) & mask) == (toIpv4Int(rule.substring(0, slash).trim()) & mask);
    }

    private int toIpv4Int(String ipv4) {
        String[] parts = ipv4.split("\\.");
        if (parts.length != 4) {
            return Integer.MIN_VALUE;   // 不是 IPv4,必然不匹配
        }
        return (Integer.parseInt(parts[0]) << 24)
                | (Integer.parseInt(parts[1]) << 16)
                | (Integer.parseInt(parts[2]) << 8)
                | Integer.parseInt(parts[3]);
    }
}
