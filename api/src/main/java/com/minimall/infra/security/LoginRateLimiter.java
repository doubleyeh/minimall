package com.minimall.infra.security;

import com.minimall.common.ErrorCode;
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
 */
@Component
public class LoginRateLimiter {

    private static final String KEY_PREFIX = "login:ip:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RedisRateLimiter rateLimiter;
    private final LoginProperties properties;

    public LoginRateLimiter(RedisRateLimiter rateLimiter, LoginProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /** 取不到 IP 时不计数:宁可漏限一次,也不要让所有请求共用同一个空 key 互相挤掉。 */
    public void checkAndCount(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        rateLimiter.checkAndCount(KEY_PREFIX + clientIp, properties.ipLimitPerMinute(), WINDOW,
                ErrorCode.IP_RATE_LIMITED);
    }
}
