package com.minimall.sys.service.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * "哪些数据属于一个租户"(架构文档 4.11)。
 *
 * <p>导出与物理删除共用这一份清单,理由很直接:两份清单一定会走偏,而走偏的后果是
 * **删了却导不出去**(或反过来,导出了却没删干净 → 永远查不到的孤儿数据)。
 *
 * <p>两个反直觉的地方:
 * <ol>
 *   <li>表清单来自 {@code information_schema} 而不是硬编码:新增带 {@code tenant_id} 的表自动被覆盖;</li>
 *   <li>但**光看 tenant_id 不够**:{@code sys_user_role} / {@code sys_role_menu} / {@code sys_role_dept}
 *       这些关联表没有 tenant_id,要靠租户的用户与角色反查。漏掉它们就是孤儿行。</li>
 * </ol>
 *
 * <p>关联表排在前面:删除时先删它们 —— 反查依赖的还是租户的用户/角色行,先删了用户就找不到了。
 *
 * <p><b>导出范围与删除范围刻意不完全相同</b>:审计日志要留着(见 {@link #RETAINED_TABLES})。
 * 导出仍包含它 —— 存档里少了审计记录,"这个租户原来做过什么"就再也说不清了。
 */
@Component
public class TenantDataScope {

    private static final List<String> JUNCTION_TABLES = List.of(
            "sys_user_role", "sys_role_menu", "sys_role_dept");

    /**
     * 物理删除时**留下来**的表。
     *
     * <p>{@code sys_oper_log}:审计日志要保留得比业务数据久。它是"这个租户当时做过什么"的唯一凭据,
     * 而注销之后业务数据都不在了,一旦再删日志,任何争议都无法回溯。
     *
     * <p>留下的日志会带着一个已不存在的 tenant_id,这是可以接受的:雪花 ID 不复用,所以不会被后来
     * 新建的租户"认领";平台侧的操作日志页按 tenant_id 精确过滤,照样能查到它们。
     * 代价是这些行会长期留着 —— 何时归档/清理属于另一条策略(当前未实现,见架构文档第 10 节)。
     */
    private static final Set<String> RETAINED_TABLES = Set.of("sys_oper_log");

    private final JdbcTemplate jdbcTemplate;

    public TenantDataScope(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 该租户的全部数据(导出用):关联表在前,带 tenant_id 的表在后。 */
    public List<String> tables() {
        List<String> tables = new ArrayList<>(JUNCTION_TABLES);
        tables.addAll(jdbcTemplate.queryForList(
                "select table_name from information_schema.columns "
                        + "where table_schema = database() and column_name = 'tenant_id' "
                        + "order by table_name",
                String.class));
        return tables;
    }

    /** 物理删除的范围:与 {@link #tables()} 相同,但去掉要保留的表。 */
    public List<String> deletableTables() {
        return tables().stream().filter(table -> !RETAINED_TABLES.contains(table)).toList();
    }

    public String selectSql(String table) {
        return switch (table) {
            case "sys_user_role" -> "select * from sys_user_role where user_id in "
                    + "(select id from sys_user where tenant_id = ?)";
            case "sys_role_menu" -> "select * from sys_role_menu where role_id in "
                    + "(select id from sys_role where tenant_id = ?)";
            case "sys_role_dept" -> "select * from sys_role_dept where role_id in "
                    + "(select id from sys_role where tenant_id = ?)";
            default -> "select * from " + table + " where tenant_id = ?";
        };
    }

    /**
     * 与 {@link #selectSql} 一一对应:导得出什么,删掉的就是什么。
     *
     * <p>刻意把 SQL 写全而不是用字符串替换从 select 变出来:这里的条件是安全关键
     * (删错租户就是删错数据),要让人一眼能核对,而不是先在心里跑一遍替换。
     */
    public String deleteSql(String table) {
        return switch (table) {
            case "sys_user_role" -> "delete from sys_user_role where user_id in "
                    + "(select id from sys_user where tenant_id = ?)";
            case "sys_role_menu" -> "delete from sys_role_menu where role_id in "
                    + "(select id from sys_role where tenant_id = ?)";
            case "sys_role_dept" -> "delete from sys_role_dept where role_id in "
                    + "(select id from sys_role where tenant_id = ?)";
            default -> "delete from " + table + " where tenant_id = ?";
        };
    }
}
