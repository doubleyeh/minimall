package com.minimall.sys.domain;

import com.minimall.infra.persistence.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 定时任务执行历史(架构文档 6.2)。
 *
 * <p>继承 {@link BaseAuditEntity} 而不是租户基类:一次任务执行**覆盖所有租户**,
 * 它本身不属于任何租户;而且写入发生在调度线程上,那里没有租户上下文(DTO 之外的字段也不该有)。
 */
@Entity
@Table(name = "sys_task_run_log")
@Getter
@Setter
public class SysTaskRunLog extends BaseAuditEntity {

    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAILED = 2;

    /** 与 Redis 锁的键一致,便于对着 `task:lock:*` 排查。 */
    @Column(name = "task_name", nullable = false, length = 64)
    private String taskName;

    /** 1-成功 2-失败。"未抢到锁"不记录,理由见建表脚本。 */
    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    @Column(name = "duration_ms", nullable = false)
    private Long durationMs;

    @Column(name = "error_msg", length = 1000)
    private String errorMsg;
}
