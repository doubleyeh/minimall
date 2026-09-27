package com.minimall.infra.export;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV 生成(架构文档 7.6)。
 *
 * <p><b>为什么是 CSV 而不是 xlsx</b>:真 xlsx 要么引 POI/EasyExcel(这个项目的依赖面一直收得很紧),
 * 要么手写 zip+XML(易错且没人愿意维护)。CSV 用 JDK 就能出,Excel 双击也能直接打开。
 * 需要多 sheet、格式、公式时再引依赖,而不是提前引。
 *
 * <p>两个必须处理的细节:
 * <ul>
 *   <li><b>BOM</b>:不加 BOM 的话 Excel 按本地编码解析 UTF-8,中文全是乱码。
 *       代价是某些朴素解析器会在第一个表头前看到一个不可见字符 —— 这份产物是给人看的,优先顾 Excel</li>
 *   <li><b>值以 {@code = + - @} 开头要加前缀</b>:否则 Excel 会把它当公式执行,
 *       导出内容里带 {@code =cmd|...} 就变成了"导出即中招"(CSV 注入)</li>
 * </ul>
 */
public final class CsvExporter {

    private static final String BOM = "\uFEFF";
    private static final String CRLF = "\r\n";
    /** 逗号分隔;行内换行用 \r\n,与 Excel 一致 */
    private static final String DELIMITER = ",";

    private CsvExporter() {
    }

    public static byte[] toCsv(List<String> headers, List<List<String>> rows) {
        StringBuilder csv = new StringBuilder(BOM);
        csv.append(line(headers));
        for (List<String> row : rows) {
            csv.append(line(row));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String line(List<String> cells) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                builder.append(DELIMITER);
            }
            builder.append(escape(cells.get(i)));
        }
        return builder.append(CRLF).toString();
    }

    /** 含分隔符、引号、换行时必须整体加引号并把内部引号翻倍,否则列会错位。 */
    private static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String safe = neutralizeFormula(value);
        if (safe.indexOf('"') >= 0 || safe.indexOf(',') >= 0
                || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    /** 前缀一个单引号,让 Excel 当文本而不是公式。 */
    private static String neutralizeFormula(String value) {
        char first = value.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + value;
        }
        return value;
    }
}
