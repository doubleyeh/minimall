package com.minimall.sys.api.dto;

import java.time.LocalDateTime;

/**
 * 每个任务的最近一次执行情况(架构文档 6.2)。
 *
 * <p>为什么单独给一个"总览":光翻明细是**看不出"某个任务停了"的** ——
 * 那需要逐个任务名筛一遍。而这个页面的全部意义就是"任务静默停掉要有人知道",
 * 所以把"每个任务最后跑成什么样"摆在最上面。
 */
public record TaskSummaryView(
        String taskName,
        /** 该任务历史记录总数 */
        long totalRuns,
        /** 最后一次执行的开始时间;从未执行过时为 null */
        LocalDateTime lastStartTime,
        Integer lastStatus,
        String lastStatusText,
        Long lastDurationMs) {
}
