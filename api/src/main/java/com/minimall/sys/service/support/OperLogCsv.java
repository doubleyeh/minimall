package com.minimall.sys.service.support;

import com.minimall.infra.export.CsvExporter;
import com.minimall.sys.api.dto.OperLogView;

import java.util.List;

/**
 * 操作日志的 CSV 形状(架构文档 7.2、7.6)。
 *
 * <p>两条出口共用这一份定义:管理端的导出接口({@code GET /system/oper-logs/export})与
 * 到期归档任务({@link OperLogArchiver})。两处各写一份列定义一定会走偏,而走偏的表现是
 * "导出的列和归档的列不一样",排查时很费劲。
 *
 * <p>最后一列是日志 ID:归档文件里有了它,跨批次的重复行才能被识别出来(见 OperLogArchiver 的
 * "先落盘再删库"那一段)。
 */
public final class OperLogCsv {

    public static final List<String> HEADERS = List.of(
            "时间", "租户ID", "用户ID", "模块", "权限码", "方法", "结果", "错误信息", "IP", "traceId", "请求参数", "ID");

    private OperLogCsv() {
    }

    public static List<String> cells(OperLogView view) {
        return List.of(
                String.valueOf(view.createTime()),
                view.tenantId() == null ? "" : String.valueOf(view.tenantId()),
                view.userId() == null ? "" : String.valueOf(view.userId()),
                view.module(),
                view.permCode() == null ? "" : view.permCode(),
                view.method(),
                view.status() != null && view.status() == 1 ? "成功" : "失败",
                view.errorMsg() == null ? "" : view.errorMsg(),
                view.ip() == null ? "" : view.ip(),
                view.traceId() == null ? "" : view.traceId(),
                view.requestParams() == null ? "" : view.requestParams(),
                String.valueOf(view.id()));
    }

    /** 单行(含行尾换行),供"边写边删"的归档使用。 */
    public static String line(OperLogView view) {
        return CsvExporter.line(cells(view));
    }
}
