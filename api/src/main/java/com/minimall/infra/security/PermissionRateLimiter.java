package com.minimall.infra.security;

import com.minimall.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 权限刷新接口的限流(架构文档 7.1.1)。
 *
 * <p>维度是**用户**:这个接口必须先登录才能调,按 IP 计会把同一出口(公司、小程序网关)后面的
 * 多个用户算在一起。它防的是"拿着一个有效令牌反复回源算权限" ——
 * 缓存未命中时一次调用要查用户、角色、菜单三张表。
 *
 * <p>阈值比正常用量宽得多(见 {@code minimall.permission.refresh-limit-per-minute}):
 * 前端只在登录后与收到 403 之后拉,而且已经做了 10 秒限频。
 */
@Component
public class PermissionRateLimiter {

    private static final String KEY_PREFIX = "perm:refresh:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RedisRateLimiter rateLimiter;
    private final PermissionProperties properties;

    public PermissionRateLimiter(RedisRateLimiter rateLimiter, PermissionProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /** 取不到用户时不计数(调用方会先做登录校验,这里只是不把 null 拼进 key)。 */
    public void checkAndCount(Long userId) {
        if (userId == null) {
            return;
        }
        rateLimiter.checkAndCount(KEY_PREFIX + userId, properties.refreshLimitPerMinute(), WINDOW,
                ErrorCode.IP_RATE_LIMITED);
    }
}
