package com.minimall.infra.idempotent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 幂等键配置(架构文档 7.7)。
 *
 * @param ttlSeconds 幂等结果的保留时间(秒)。在这段时间内,同样的键重放同一个结果
 */
@ConfigurationProperties(prefix = "minimall.idempotency")
public record IdempotencyProperties(Integer ttlSeconds) {

    /**
     * 默认 10 分钟。够覆盖"客户端超时后重试"这个真实场景;
     * 太长则同一个键在用户改完数据后再发会拿到旧结果,反而迷惑。
     */
    private static final int DEFAULT_TTL_SECONDS = 600;

    public IdempotencyProperties {
        ttlSeconds = ttlSeconds == null ? DEFAULT_TTL_SECONDS : ttlSeconds;
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("minimall.idempotency.ttl-seconds 必须为正数");
        }
    }
}
