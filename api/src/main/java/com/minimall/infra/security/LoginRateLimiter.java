package com.minimall.infra.security;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * IP 维度登录限流(架构文档 7.1.1)。
 *
 * <p>它防的是**自动化撞库脚本**(同一个 IP 短时间内试大量账号),所以按 IP 计数、
 * 不区分租户和用户名;与"账号维度失败 5 次锁定"是两层完全独立的防护,不要合并成一套计数器
 * ——一个防"扫大量账号",一个防"死磕一个账号"。
 *
 * <p>超过阈值直接抛 429,**请求根本不进入账号级校验逻辑**(连租户都不查),这样限流的开销最小,
 * 也不会因为限流逻辑本身去查库而被放大成放大攻击面。
 *
 * <p>为什么用 Redis 而不是本地计数:多实例部署时本地计数等于把阈值乘以实例数,
 * 攻击者只要换实例打就能绕开。
 */
@Component
public class LoginRateLimiter {

    private static final String KEY_PREFIX = "login:ip:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redis;
    private final LoginProperties properties;

    public LoginRateLimiter(StringRedisTemplate redis, LoginProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 计数并判断,超限抛 {@link ErrorCode#IP_RATE_LIMITED}。
     *
     * <p>用 {@code INCR} + 首次设置过期时间实现固定窗口:实现简单、原子,不需要额外的清理任务;
     * 代价是窗口边界上最多可能放过 2 倍流量——对"防脚本"这个目标来说完全可以接受,
     * 不值得为此上滑动窗口。
     */
    public void checkAndCount(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        String key = KEY_PREFIX + clientIp;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, WINDOW);
        }
        if (count != null && count > properties.ipLimitPerMinute()) {
            throw new BusinessException(ErrorCode.IP_RATE_LIMITED);
        }
    }
}
