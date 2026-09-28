package com.minimall.infra.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定时任务执行历史的保留期(架构文档 6.2)。
 *
 * <p>不设保留期的话这张表会"只增不减":一分钟一次的任务一年就是几十万行 ——
 * 与审计日志是同一类问题(见 {@code minimall.audit.archive-after-days})。
 */
@ConfigurationProperties(prefix = "minimall.task-log")
public record TaskLogProperties(Integer retainDays) {

    private static final int DEFAULT_RETAIN_DAYS = 30;

    public TaskLogProperties {
        retainDays = retainDays == null ? DEFAULT_RETAIN_DAYS : retainDays;
        if (retainDays <= 0) {
            throw new IllegalArgumentException("minimall.task-log.retain-days 必须为正数");
        }
    }
}
