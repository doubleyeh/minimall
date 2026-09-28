package com.minimall.infra.schedule;

import com.minimall.common.PageResult;
import com.minimall.sys.api.dto.TaskRunLogView;
import com.minimall.sys.api.dto.TaskSummaryView;
import com.minimall.sys.domain.SysTaskRunLog;
import com.minimall.sys.domain.repository.SysTaskRunLogRepository;
import com.minimall.sys.service.TaskRunLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 定时任务执行历史(架构文档 6.2)。
 *
 * <p>为什么值得有这么一套:7 个任务此前只有日志,任务挂了或者某个任务从某时刻起不再执行,
 * **没有任何人会知道**。埋点放在 {@link ScheduledTaskLock}(所有任务共用的咽喉),
 * 所以这里测的是那个咽喉,而不是每个任务各测一遍。
 */
@SpringBootTest
@ActiveProfiles("test")
class ScheduledTaskRunLogIntegrationTest {

    @Autowired
    private ScheduledTaskLock taskLock;
    @Autowired
    private SysTaskRunLogRepository runLogRepository;
    @Autowired
    private TaskRunLogService taskRunLogService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 本类造出来的任务名,用来精确清理(这张表是平台级的,别删到别的用例的行)。 */
    private String newTaskName() {
        return "用例任务" + System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        redis.delete(redis.keys("task:lock:*"));
        runLogRepository.findAll().stream()
                .filter(row -> row.getTaskName() != null && row.getTaskName().startsWith("用例任务"))
                .forEach(runLogRepository::delete);
    }

    @Test
    @DisplayName("执行成功:落一条成功记录,带开始时间与耗时")
    void recordsSuccess() {
        String taskName = newTaskName();

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        assertThat(taskLock.runIfNotLocked(taskName, () -> { })).isTrue();

        List<SysTaskRunLog> rows = rowsOf(taskName);
        assertThat(rows).hasSize(1);
        SysTaskRunLog row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo(SysTaskRunLog.STATUS_SUCCESS);
        assertThat(row.getStartTime()).isAfter(before);
        assertThat(row.getEndTime()).isAfterOrEqualTo(row.getStartTime());
        assertThat(row.getDurationMs()).isNotNegative();
        assertThat(row.getErrorMsg()).isNull();
    }

    @Test
    @DisplayName("执行失败:落一条失败记录并带异常摘要,且异常照旧往外抛")
    void recordsFailureAndStillThrows() {
        String taskName = newTaskName();

        assertThatThrownBy(() -> taskLock.runIfNotLocked(taskName, () -> {
            throw new IllegalStateException("故意失败");
        }))
                .as("吞掉异常会让'任务失败了'更隐蔽,原来的行为必须保持")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("故意失败");

        List<SysTaskRunLog> rows = rowsOf(taskName);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(SysTaskRunLog.STATUS_FAILED);
        assertThat(rows.get(0).getErrorMsg())
                .as("错误摘要要能直接看出是什么错,而不是只写一句'失败了'")
                .contains("IllegalStateException")
                .contains("故意失败");
    }

    @Test
    @DisplayName("没抢到锁时跳过,并且不记录")
    void skippedRunIsNotRecorded() {
        String taskName = newTaskName();

        // 在"已经持有锁"的任务里再申请同一把锁 —— 等价于别的实例正在跑
        boolean innerRan = taskLock.runIfNotLocked(taskName, () -> {
            boolean inner = taskLock.runIfNotLocked(taskName, () -> { });
            assertThat(inner).as("锁已在别人手里,内层不该执行").isFalse();
        });

        assertThat(innerRan).isTrue();
        assertThat(rowsOf(taskName))
                .as("只该有外层那一条:跳过记录在多实例下每周期都产生,记了就是纯噪声")
                .hasSize(1);
        assertThat(rowsOf(taskName).get(0).getStatus()).isEqualTo(SysTaskRunLog.STATUS_SUCCESS);
    }

    @Test
    @DisplayName("保留期清理:只删过期的,新鲜的不动")
    void pruneDeletesOnlyExpired() {
        String taskName = newTaskName();
        taskLock.runIfNotLocked(taskName, () -> { });
        taskLock.runIfNotLocked(taskName, () -> { });
        assertThat(rowsOf(taskName)).hasSize(2);

        // 把其中一条推到 40 天前(create_time 由审计填充,业务代码改不了,只能动 SQL)
        List<SysTaskRunLog> rows = rowsOf(taskName);
        jdbcTemplate.update("update sys_task_run_log set create_time = ? where id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusDays(40)), rows.get(0).getId());

        int deleted = taskRunLogService.prune(30);

        assertThat(deleted).isEqualTo(1);
        assertThat(rowsOf(taskName)).as("没过期的那条要留着").hasSize(1);
    }

    @Test
    @DisplayName("查询:按任务名与结果筛")
    void pageFiltersByTaskNameAndStatus() {
        String taskName = newTaskName();
        taskLock.runIfNotLocked(taskName, () -> { });
        assertThatThrownBy(() -> taskLock.runIfNotLocked(taskName, () -> {
            throw new IllegalStateException("故意失败");
        })).isInstanceOf(IllegalStateException.class);

        PageResult<TaskRunLogView> all = taskRunLogService.page(taskName, null, 1, 10);
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.list().get(0).statusText()).as("失败排在最前(按开始时间倒序)").isEqualTo("失败");
        assertThat(all.list().get(1).statusText()).isEqualTo("成功");

        PageResult<TaskRunLogView> onlyFailed = taskRunLogService.page(taskName, 2, 1, 10);
        assertThat(onlyFailed.total()).isEqualTo(1);
        assertThat(onlyFailed.list().get(0).errorMsg()).contains("故意失败");

        PageResult<TaskRunLogView> otherTask = taskRunLogService.page(newTaskName(), null, 1, 10);
        assertThat(otherTask.total()).as("任务名精确匹配").isZero();
    }

    @Test
    @DisplayName("任务总览:给出每个任务的最后执行时间与结果 —— 页面上「谁停了」靠它看出来")
    void summariesShowLastRunPerTask() {
        String taskName = newTaskName();
        taskLock.runIfNotLocked(taskName, () -> { });
        assertThatThrownBy(() -> taskLock.runIfNotLocked(taskName, () -> {
            throw new IllegalStateException("故意失败");
        })).isInstanceOf(IllegalStateException.class);

        // 不断言"只有它"、也不断言任务总数:同一张表里还会有别的用例跑出来的任务名
        TaskSummaryView summary = taskRunLogService.summaries().stream()
                .filter(item -> taskName.equals(item.taskName()))
                .findFirst()
                .orElseThrow();

        assertThat(summary.totalRuns()).isEqualTo(2);
        assertThat(summary.lastStatus()).as("最后一次是失败").isEqualTo(SysTaskRunLog.STATUS_FAILED);
        assertThat(summary.lastStatusText()).isEqualTo("失败");
        assertThat(summary.lastStartTime()).isNotNull();
    }

    private List<SysTaskRunLog> rowsOf(String taskName) {
        return runLogRepository.findAll().stream()
                .filter(row -> taskName.equals(row.getTaskName()))
                .toList();
    }
}
