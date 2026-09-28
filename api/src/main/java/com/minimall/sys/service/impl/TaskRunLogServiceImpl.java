package com.minimall.sys.service.impl;

import com.minimall.common.PageResult;
import com.minimall.sys.api.dto.TaskRunLogView;
import com.minimall.sys.api.dto.TaskSummaryView;
import com.minimall.sys.domain.QSysTaskRunLog;
import com.minimall.sys.domain.SysTaskRunLog;
import com.minimall.sys.domain.repository.SysTaskRunLogRepository;
import com.minimall.sys.service.TaskRunLogService;
import com.querydsl.core.BooleanBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务执行历史实现(架构文档 6.2)。
 */
@Service
@Transactional
public class TaskRunLogServiceImpl implements TaskRunLogService {

    private final SysTaskRunLogRepository repository;

    public TaskRunLogServiceImpl(SysTaskRunLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public PageResult<TaskRunLogView> page(String taskName, Integer status, int pageNo, int pageSize) {
        QSysTaskRunLog qLog = QSysTaskRunLog.sysTaskRunLog;
        BooleanBuilder where = new BooleanBuilder();
        if (taskName != null && !taskName.isBlank()) {
            where.and(qLog.taskName.eq(taskName.trim()));
        }
        if (status != null) {
            where.and(qLog.status.eq(status));
        }
        Page<SysTaskRunLog> page = repository.findAll(where, PageRequest.of(
                Math.max(pageNo - 1, 0),
                Math.max(pageSize, 1),
                // 按开始时间倒序:排查时看的是"最近几次跑成什么样",而自增 ID 在并发写入时未必等于时间序
                Sort.by(Sort.Direction.DESC, "startTime").and(Sort.by(Sort.Direction.DESC, "id"))));
        List<TaskRunLogView> views = page.getContent().stream()
                .map(TaskRunLogServiceImpl::toView)
                .toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public List<TaskSummaryView> summaries() {
        // 复用 page 而不是再写一条聚合查询:任务名只有个位数,一次一行足够;
        // 而"最后一次是失败还是成功"用现成的分页查询最省事(自带排序与状态文案)
        return repository.findDistinctTaskNames().stream()
                .map(this::summaryOf)
                .toList();
    }

    private TaskSummaryView summaryOf(String taskName) {
        PageResult<TaskRunLogView> latest = page(taskName, null, 1, 1);
        TaskRunLogView last = latest.list().isEmpty() ? null : latest.list().get(0);
        return new TaskSummaryView(taskName, latest.total(),
                last == null ? null : last.startTime(),
                last == null ? null : last.status(),
                last == null ? null : last.statusText(),
                last == null ? null : last.durationMs());
    }

    @Override
    public int prune(int retainDays) {
        return repository.deleteCreatedBefore(LocalDateTime.now().minusDays(retainDays));
    }

    private static TaskRunLogView toView(SysTaskRunLog log) {
        return new TaskRunLogView(log.getId(), log.getTaskName(), log.getStatus(),
                TaskRunLogView.text(log.getStatus()), log.getStartTime(), log.getEndTime(),
                log.getDurationMs(), log.getErrorMsg());
    }
}
