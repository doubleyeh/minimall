package com.minimall.sys.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.sys.api.dto.OperLogQuery;
import com.minimall.sys.api.dto.OperLogView;
import com.minimall.infra.export.CsvExporter;
import com.minimall.sys.service.OperLogService;
import com.minimall.sys.service.support.OperLogCsv;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    /** 导出上限。超过就报错让调用方缩小范围,不静默截断(见 service 的说明)。 */
    private static final int EXPORT_MAX_ROWS = 10000;

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

    /**
     * 导出当前筛选条件下的日志(CSV)。
     *
     * <p>权限码复用查询那一个:导出就是把"能看到的"读出去,单独开一个码只会变成
     * "同一个页面里两个权限点各自维护"。真正需要收的是"谁能看日志"。
     *
     * <p>响应体是文件字节,不套统一响应体(套了 Excel 打开就是一堆 JSON)。
     */
    @GetMapping("/export")
    @SaCheckPermission("system:operlog:list")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endTime) {
        OperLogQuery query = new OperLogQuery(tenantId, userId, module, status, startTime, endTime, 1, 1);
        List<OperLogView> rows = operLogService.listForExport(query, EXPORT_MAX_ROWS);

        // 列定义与归档任务共用一处(OperLogCsv),避免"导出的列"和"归档的列"走偏
        byte[] csv = CsvExporter.toCsv(OperLogCsv.HEADERS,
                rows.stream().map(OperLogCsv::cells).toList());
        return ResponseEntity.ok()
                // 文件名用纯 ASCII:中文文件名要按 RFC 5987 编码,各家浏览器行为还不一致,没必要
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"oper-log-" + LocalDate.now() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

}
