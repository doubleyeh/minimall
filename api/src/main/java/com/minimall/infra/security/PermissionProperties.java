package com.minimall.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 权限缓存配置(架构文档 9.3)。
 *
 * <p>TTL 与会话超时对齐,避免出现"会话还在、权限缓存已过期"导致的抖动:
 * 那种情况下每个请求都会回源查库算权限,而且恰好发生在"权限刚被收回"的窗口里。
 *
 * @param cacheTtlSeconds 权限缓存 TTL,默认 7200 秒
 */
@ConfigurationProperties(prefix = "minimall.permission")
public record PermissionProperties(Integer cacheTtlSeconds) {

    private static final int DEFAULT_TTL_SECONDS = 7200;

    public PermissionProperties {
        cacheTtlSeconds = cacheTtlSeconds == null ? DEFAULT_TTL_SECONDS : cacheTtlSeconds;
        if (cacheTtlSeconds <= 0) {
            throw new IllegalArgumentException("minimall.permission.cache-ttl-seconds 必须为正数,当前=" + cacheTtlSeconds);
        }
    }
}
