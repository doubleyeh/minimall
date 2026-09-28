package com.minimall.infra.schedule;

import com.minimall.sys.domain.SysTaskRunLog;
import com.minimall.sys.domain.repository.SysTaskRunLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 记录定时任务的执行结果(架构文档 6.2)。
 *
 * <p>为什么独立一个组件而不是直接写在 {@link ScheduledTaskLock} 里:锁的职责是互斥,
 * 记录是观测。而且"记日志失败绝不能影响任务本身"这件事需要一个 try/catch 兜住,
 * 混在锁里会让那段本就绕的代码更难读。
 *
 * <p>写在调度线程上、且本表是平台级的(见实体注释),所以不需要租户上下文 ——
 * 调度线程上没有它。这一点很关键:`sys_user` 那类挂了租户过滤器的实体在这里查询会被拦成空集,
 * 而写一个带 {@code tenant_id} 非空的实体则会直接抛异常。
 */
@Component
public class TaskRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(TaskRunRecorder.class);

    /** 与 {@code sys_task_run_log.error_msg} 的列宽一致。 */
    private static final int MAX_ERROR_LENGTH = 1000;

    private final SysTaskRunLogRepository repository;

    public TaskRunRecorder(SysTaskRunLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 记一次执行。{@code error} 为空表示成功。
     *
     * <p>**本方法不抛异常**:记录是观测手段,不能因为写不进去就把任务自己的成败改写掉。
     */
    public void record(String taskName, LocalDateTime start, LocalDateTime end, Throwable error) {
        if (error != null) {
            // 失败同时打一条 ERROR:这是"没有告警通道"的项目里,日志侧唯一的抓手
            log.error("定时任务「{}」执行失败,耗时 {}ms", taskName,
                    Duration.between(start, end).toMillis(), error);
        }
        try {
            SysTaskRunLog runLog = new SysTaskRunLog();
            runLog.setTaskName(taskName);
            runLog.setStatus(error == null ? SysTaskRunLog.STATUS_SUCCESS : SysTaskRunLog.STATUS_FAILED);
            runLog.setStartTime(start);
            runLog.setEndTime(end);
            runLog.setDurationMs(Duration.between(start, end).toMillis());
            runLog.setErrorMsg(describe(error));
            repository.save(runLog);
        } catch (Exception ex) {
            log.warn("定时任务执行历史写入失败(任务本身的执行结果不受影响):task={} cause={}",
                    taskName, ex.getMessage());
        }
    }

    private String describe(Throwable error) {
        if (error == null) {
            return null;
        }
        String message = error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : ": " + error.getMessage());
        return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
    }
}
