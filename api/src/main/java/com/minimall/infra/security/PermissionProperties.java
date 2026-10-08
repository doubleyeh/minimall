package com.minimall.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 权限缓存配置(架构文档 9.3、5.5)。
 *
 * <p>TTL 只是"版本号没变时不回源"的兜底,不决定权限何时生效(那是版本号的事)。
 * 默认 7200 秒,与访问令牌 TTL(dev 86400、test/prod 1800)**并不相等**。
 *
 * @param cacheTtlSeconds        权限缓存 TTL,默认 7200 秒
 * @param refreshLimitPerMinute  权限刷新接口的每人每分钟上限(默认 30)。前端只在登录后与收到 403
 *                               之后拉,正常用量远低于这个数;限的是"拿有效令牌反复回源算权限"
 */
@ConfigurationProperties(prefix = "minimall.permission")
public record PermissionProperties(Integer cacheTtlSeconds, Integer refreshLimitPerMinute) {

    private static final int DEFAULT_TTL_SECONDS = 7200;
    private static final int DEFAULT_REFRESH_LIMIT_PER_MINUTE = 30;

    public PermissionProperties {
        cacheTtlSeconds = cacheTtlSeconds == null ? DEFAULT_TTL_SECONDS : cacheTtlSeconds;
        refreshLimitPerMinute = refreshLimitPerMinute == null
                ? DEFAULT_REFRESH_LIMIT_PER_MINUTE : refreshLimitPerMinute;
        if (cacheTtlSeconds <= 0) {
            throw new IllegalArgumentException("minimall.permission.cache-ttl-seconds 必须为正数,当前=" + cacheTtlSeconds);
        }
        if (refreshLimitPerMinute <= 0) {
            throw new IllegalArgumentException(
                    "minimall.permission.refresh-limit-per-minute 必须为正数,当前=" + refreshLimitPerMinute);
        }
    }
}
