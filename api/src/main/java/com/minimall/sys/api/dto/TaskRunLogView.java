package com.minimall.sys.api.dto;

import java.time.LocalDateTime;

/**
 * 定时任务执行历史(架构文档 6.2)。
 *
 * <p>{@code statusText} 在服务端拼好 —— 与积分流水同样的理由:这是给运营看的文案,
 * 不该让前端各自维护一份映射。
 */
public record TaskRunLogView(
        Long id,
        String taskName,
        Integer status,
        String statusText,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Long durationMs,
        String errorMsg) {

    public static String text(Integer status) {
        if (status == null) {
            return "未知";
        }
        return status == 2 ? "失败" : "成功";
    }
}
