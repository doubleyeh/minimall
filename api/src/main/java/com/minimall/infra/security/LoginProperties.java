package com.minimall.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录防护配置(架构文档 7.1.1)。
 *
 * <p>这里的两层防护是**独立**的,不要合并成一套计数器:
 * @param maxFailCount   账号维度的失败阈值(默认 5):防"死磕一个账号"
 * @param lockMinutes    锁定时长(默认 15 分钟):现阶段固定值,不做"随失败次数递增"的升级策略
 * @param ipLimitPerMinute IP 维度每分钟上限(默认 10):防"扫大量账号"的撞库脚本,
 *                       不区分租户和用户名
 */
@ConfigurationProperties(prefix = "minimall.login")
public record LoginProperties(Integer maxFailCount, Integer lockMinutes, Integer ipLimitPerMinute) {

    private static final int DEFAULT_MAX_FAIL_COUNT = 5;
    private static final int DEFAULT_LOCK_MINUTES = 15;
    private static final int DEFAULT_IP_LIMIT_PER_MINUTE = 10;

    public LoginProperties {
        maxFailCount = maxFailCount == null ? DEFAULT_MAX_FAIL_COUNT : maxFailCount;
        lockMinutes = lockMinutes == null ? DEFAULT_LOCK_MINUTES : lockMinutes;
        ipLimitPerMinute = ipLimitPerMinute == null ? DEFAULT_IP_LIMIT_PER_MINUTE : ipLimitPerMinute;
        if (maxFailCount <= 0 || lockMinutes <= 0 || ipLimitPerMinute <= 0) {
            throw new IllegalArgumentException("minimall.login.* 必须为正数");
        }
    }
}
