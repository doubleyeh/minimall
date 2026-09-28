package com.minimall.sys.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.sys.api.dto.TaskRunLogView;
import com.minimall.sys.api.dto.TaskSummaryView;
import com.minimall.sys.service.TaskRunLogService;
import org.springframework.web.bind.annotation.GetMapping;
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

    public SysTaskLogController(TaskRunLogService taskRunLogService) {
        this.taskRunLogService = taskRunLogService;
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
    @GetMapping("/summaries")
    @SaCheckPermission("system:tasklog:list")
    public ApiResponse<List<TaskSummaryView>> summaries() {
        return ApiResponse.ok(taskRunLogService.summaries());
    }
}
