package com.minimall.sys.service.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
 */
@Component
public class TenantDataScope {

    private static final List<String> JUNCTION_TABLES = List.of(
            "sys_user_role", "sys_role_menu", "sys_role_dept");

    private final JdbcTemplate jdbcTemplate;

    public TenantDataScope(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 关联表在前,带 tenant_id 的表在后(顺序对删除有意义,对导出没有)。 */
    public List<String> tables() {
        List<String> tables = new ArrayList<>(JUNCTION_TABLES);
        tables.addAll(jdbcTemplate.queryForList(
                "select table_name from information_schema.columns "
                        + "where table_schema = database() and column_name = 'tenant_id' "
                        + "order by table_name",
                String.class));
        return tables;
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
