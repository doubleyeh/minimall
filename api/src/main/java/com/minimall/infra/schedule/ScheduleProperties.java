package com.minimall.infra.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 定时任务配置(架构文档 6.2)。
 *
 * @param taskLockSeconds 任务互斥锁的存活时间(秒)
 */
@ConfigurationProperties(prefix = "minimall.schedule")
public record ScheduleProperties(Integer taskLockSeconds) {

    /**
     * 默认 30 分钟。取值要**明显大于任务最长耗时**,否则跑得久的任务还没结束锁就过期、
     * 另一个实例跟着跑起来,互斥等于没有;代价是进程被强杀后最多等这么久才会有人接手。
     */
    private static final int DEFAULT_TASK_LOCK_SECONDS = 30 * 60;

    public ScheduleProperties {
        taskLockSeconds = taskLockSeconds == null ? DEFAULT_TASK_LOCK_SECONDS : taskLockSeconds;
        if (taskLockSeconds <= 0) {
            throw new IllegalArgumentException("minimall.schedule.task-lock-seconds 必须为正数");
        }
    }
}
