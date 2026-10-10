package com.minimall.sys.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.common.ApiResponse;
import com.minimall.infra.audit.AuditLog;
import com.minimall.infra.schedule.ManualTaskRegistry;
import com.minimall.common.PageResult;
import com.minimall.sys.api.dto.TaskRunLogView;
import com.minimall.sys.api.dto.TaskSummaryView;
import com.minimall.sys.service.TaskRunLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台端 - 定时任务执行历史(架构文档 6.2)。
 *
 * <p>平台级接口:一次任务执行覆盖所有租户,而"某个任务停了"是平台运维要发现的事。
 * 权限码 `system:tasklog:list` 挂在平台菜单下(V11),租户角色拿不到。
 */
@RestController
@RequestMapping("/system/task-run-logs")
public class SysTaskLogController {

    private final TaskRunLogService taskRunLogService;
    private final ManualTaskRegistry manualTaskRegistry;

    public SysTaskLogController(TaskRunLogService taskRunLogService, ManualTaskRegistry manualTaskRegistry) {
        this.taskRunLogService = taskRunLogService;
        this.manualTaskRegistry = manualTaskRegistry;
    }

    @GetMapping
    @SaCheckPermission("system:tasklog:list")
    public ApiResponse<PageResult<TaskRunLogView>> list(@RequestParam(required = false) String taskName,
                                                        @RequestParam(required = false) Integer status,
                                                        @RequestParam(defaultValue = "1") int pageNo,
                                                        @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(taskRunLogService.page(taskName, status, pageNo, pageSize));
    }

    /**
     * 每个任务的最近一次执行情况,给页面顶部的总览用。
     *
     * <p>只翻明细是看不出"某个任务停了"的 —— 那要逐个任务名筛一遍。
     */
    /**
     * 可手动触发的任务名。**危险任务不在名单里**(物理删数据、归档日志、批量禁用租户),
     * 前端据此决定显示哪些「立即执行」按钮。
     */
    @GetMapping("/manual")
    @SaCheckPermission("system:tasklog:list")
    public ApiResponse<List<String>> manualTasks() {
        return ApiResponse.ok(manualTaskRegistry.names());
    }

    /**
     * 立即执行一次。与到点执行共用同一把 Redis 锁:已有实例在跑时这次会被跳过并记进历史。
     */
    @PostMapping("/{taskName}/trigger")
    @SaCheckPermission("system:tasklog:trigger")
    @AuditLog(module = "定时任务", permCode = "system:tasklog:trigger")
    public ApiResponse<Void> trigger(@PathVariable String taskName) {
        manualTaskRegistry.trigger(taskName);
        return ApiResponse.ok();
    }

    @GetMapping("/summaries")
    @SaCheckPermission("system:tasklog:list")
    public ApiResponse<List<TaskSummaryView>> summaries() {
        return ApiResponse.ok(taskRunLogService.summaries());
    }
}
