package com.minimall.infra.security;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 固定窗口计数限流(架构文档 7.1.1)。
 *
 * <p>把"INCR + 首次设过期"这段机制收在一处:限流是安全相关的东西,写两份的结果是
 * 修好一处、另一处还留着老毛病。**口径**(按什么维度计数、阈值多少、窗口多长、抛哪个错误码)
 * 由调用方决定,这里只管计数。
 *
 * <p>用 Redis 而不是本地计数:多实例部署时本地计数等于把阈值乘以实例数,攻击者换实例打就能绕开。
 *
 * <p>固定窗口的代价是边界上最多放过 2 倍流量 —— 对"防脚本/防回源"这个目标完全可以接受,
 * 不值得为此上滑动窗口。
 */
@Component
public class RedisRateLimiter {

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 计数并判断,超过 {@code threshold} 抛 {@code errorCode}。
     *
     * @param key       计数键。调用方负责把"维度"拼进去(如 {@code login:ip:1.2.3.4})
     * @param threshold 窗口内的最大次数(含)
     */
    public void checkAndCount(String key, int threshold, Duration window, ErrorCode errorCode) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, window);
        }
        if (count != null && count > threshold) {
            throw new BusinessException(errorCode);
        }
    }
}
