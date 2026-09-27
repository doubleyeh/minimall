package com.minimall.sys.service.support;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.export.CsvExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 租户数据导出(架构文档 4.11):把该租户的全部数据导成一个 ZIP(每张表一个 CSV)。
 *
 * <p><b>为什么必须有它</b>:物理删除是不可逆的,而"删之前把数据留一份"是这类流程唯一的安全垫。
 * 没有导出能力的话,注销就只剩"敢不敢删"这一个判断了。
 *
 * <p>两个实现上的选择:
 * <ol>
 *   <li><b>表清单来自 information_schema,不是硬编码的表名数组</b>:以后新增带 {@code tenant_id}
 *       的表会自动被覆盖。硬编码的清单一定会漏 —— 漏掉的那张表在删除后就是永远查不到的孤儿数据</li>
 *   <li><b>账号凭据列(密码哈希)不导出</b>:存档的用途是"查得回来",而密码哈希既不能恢复、又是
 *       最敏感的一类数据。导出去等于给存档文件额外加了一层泄露风险</li>
 * </ol>
 *
 * <p>取哪些表由 {@link TenantDataScope} 统一给出(与物理删除共用一份清单)。
 */
@Component
public class TenantDataExporter {

    private static final Logger log = LoggerFactory.getLogger(TenantDataExporter.class);
    /** 不导出内容、只留占位的列(见类注释)。 */
    private static final Set<String> CREDENTIAL_COLUMNS = Set.of("password", "password_history");

    private final JdbcTemplate jdbcTemplate;
    private final TenantDataScope dataScope;

    public TenantDataExporter(JdbcTemplate jdbcTemplate, TenantDataScope dataScope) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataScope = dataScope;
    }

    /** 打包该租户的全量数据。数据库里没有这个租户时抛 404,而不是给一个空包。 */
    public byte[] export(Long tenantId) {
        List<String> tables = dataScope.tables();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> manifest = new ArrayList<>();

        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String table : tables) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(dataScope.selectSql(table), tenantId);
                zip.putNextEntry(new ZipEntry(table + ".csv"));
                zip.write(toCsv(table, rows));
                zip.closeEntry();
                manifest.add(table + "," + rows.size());
            }
            zip.putNextEntry(new ZipEntry("manifest.txt"));
            zip.write(("表,行数\n" + String.join("\n", manifest)
                    + "\n注:密码哈希等凭据列不导出,值为 ***。\n").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException ex) {
            log.error("导出租户数据失败 tenantId={}", tenantId, ex);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "导出失败");
        }
        return out.toByteArray();
    }

    private byte[] toCsv(String table, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            // 空表也要落一个文件:存档里"这张表没有数据"和"这张表被漏了"必须能区分
            List<String> columns = columnsOf(table);
            return CsvExporter.toCsv(columns, List.of());
        }
        List<String> columns = new ArrayList<>(rows.get(0).keySet());
        List<List<String>> cells = rows.stream()
                .map(row -> columns.stream().map(column -> valueOf(row, column)).toList())
                .toList();
        return CsvExporter.toCsv(columns, cells);
    }

    private List<String> columnsOf(String table) {
        return jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = database() and table_name = ? order by ordinal_position",
                String.class, table);
    }

    private String valueOf(Map<String, Object> row, String column) {
        if (CREDENTIAL_COLUMNS.contains(column)) {
            return "***";
        }
        Object value = row.get(column);
        return value == null ? "" : String.valueOf(value);
    }
}
