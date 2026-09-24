package com.minimall.sys.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.sys.api.dto.OperLogQuery;
import com.minimall.sys.api.dto.OperLogView;
import com.minimall.sys.service.OperLogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 操作日志查询接口(架构文档 7.2)。
 *
 * <p>只提供查询:**没有修改与删除**。日志是审计凭据,能改就等于没有审计。
 *
 * <p>不额外做租户校验:查询本身走实体的租户过滤,超管身份由上下文豁免(4.6.1)。
 */
@RestController
@RequestMapping("/system/oper-logs")
public class SysOperLogController {

    private final OperLogService operLogService;

    public SysOperLogController(OperLogService operLogService) {
        this.operLogService = operLogService;
    }

    @GetMapping
    @SaCheckPermission("system:operlog:list")
    public ApiResponse<PageResult<OperLogView>> page(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endTime,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        OperLogQuery query = new OperLogQuery(tenantId, userId, module, status, startTime, endTime, pageNo, pageSize);
        return ApiResponse.ok(operLogService.page(query));
    }
}
