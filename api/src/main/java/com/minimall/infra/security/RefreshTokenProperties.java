package com.minimall.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 刷新令牌配置(架构文档 7.1.3)。
 *
 * @param ttlSeconds        刷新令牌有效期,prod 默认 7 天
 * @param reuseGraceSeconds 已用令牌的重放宽限期,默认 120 秒。**不要调大**:
 *                          它只是为"客户端网络超时后重试"留的窗口,窗口越大,
 *                          一个被盗令牌在轮换后还能继续用的时间就越长
 */
@ConfigurationProperties(prefix = "minimall.auth.refresh-token")
public record RefreshTokenProperties(Integer ttlSeconds, Integer reuseGraceSeconds) {

    private static final int DEFAULT_TTL_SECONDS = 7 * 24 * 3600;
    private static final int DEFAULT_REUSE_GRACE_SECONDS = 120;

    public RefreshTokenProperties {
        ttlSeconds = ttlSeconds == null ? DEFAULT_TTL_SECONDS : ttlSeconds;
        reuseGraceSeconds = reuseGraceSeconds == null ? DEFAULT_REUSE_GRACE_SECONDS : reuseGraceSeconds;
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("minimall.auth.refresh-token.ttl-seconds 必须为正数,当前=" + ttlSeconds);
        }
        if (reuseGraceSeconds < 0) {
            throw new IllegalArgumentException("minimall.auth.refresh-token.reuse-grace-seconds 不能为负数,当前=" + reuseGraceSeconds);
        }
    }
}
