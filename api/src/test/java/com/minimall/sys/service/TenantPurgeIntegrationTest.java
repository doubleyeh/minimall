package com.minimall.sys.service;

import com.minimall.common.BusinessException;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import com.minimall.sys.service.support.TenantDataExporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户注销、数据存档与物理清理(架构文档 4.11)。
 *
 * <p>这是全项目**唯一不可逆**的功能,所以用例的重点不是"能不能删",而是三件容易出事的事:
 * <ol>
 *   <li><b>只删该删的</b>:另一个租户的数据必须一行不少(删错租户是灾难,不是 bug);</li>
 *   <li><b>删得干净</b>:关联表(没有 tenant_id 的那几张)也要清掉,否则留下永远查不到的孤儿行;</li>
 *   <li><b>删之前导得出来</b>:存档里要有各表数据,但**不能有密码哈希**。</li>
 * </ol>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class TenantPurgeIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    private static final long FULL_PACKAGE_ID = 1L;

    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantDataExporter tenantDataExporter;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;
    private long otherTenantId;
    /** 该租户的管理员用户名:导出断言要知道"该出现的是什么数据"。 */
    private String tenantUsername;

    @BeforeEach
    void setUpTenants() {
        TenantCreateResponse tenant = createTenant();
        tenantId = tenant.tenantId();
        tenantUsername = tenant.adminUsername();
        // 另一个租户用来验"别删错":它全程不该被碰
        otherTenantId = createTenant().tenantId();
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("注销必须两步:启用中的租户不能直接注销,先禁用才行")
    void closeRequiresDisabledTenant() {
        assertThatThrownBy(() -> asSuperUser(() -> {
            tenantService.close(tenantId);
            return null;
        }))
                .as("一步到位太容易误点,而注销的终点是不可逆的物理删除")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先禁用租户");

        asSuperUserRun(() -> tenantService.changeStatus(tenantId, 0));
        asSuperUserRun(() -> tenantService.close(tenantId));

        LocalDateTime purgeAt = purgeAtOf(tenantId);
        assertThat(purgeAt).as("注销要记下清理时间(默认 3 个月后)").isNotNull();
        assertThat(purgeAt).isAfter(LocalDateTime.now().plusDays(80));
    }

    @Test
    @DisplayName("保留期内可以取消注销:数据一行没动,清掉清理时间即可")
    void cancelCloseKeepsData() {
        asSuperUserRun(() -> tenantService.changeStatus(tenantId, 0));
        asSuperUserRun(() -> tenantService.close(tenantId));

        asSuperUserRun(() -> tenantService.cancelClose(tenantId));

        assertThat(purgeAtOf(tenantId)).isNull();
        assertThat(countUsersOf(tenantId)).as("取消注销不该动数据").isPositive();
        assertThatThrownBy(() -> asSuperUser(() -> {
            tenantService.cancelClose(tenantId);
            return null;
        })).isInstanceOf(BusinessException.class).hasMessageContaining("没有处于注销状态");
    }

    @Test
    @DisplayName("到期清理:该租户的数据与关联行一起删掉,另一个租户一行不动")
    void purgeDeletesOnlyThatTenant() {
        long otherUsersBefore = countUsersOf(otherTenantId);
        assertThat(countUsersOf(tenantId)).as("先确认确实有数据可删").isPositive();
        assertThat(countJunction("sys_user_role", tenantId)).as("关联表也有数据").isPositive();
        insertOperLog(tenantId);

        backdatePurgeAt(tenantId);
        int purged = asSuperUser(tenantService::purgeExpiredTenants);

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(countUsersOf(tenantId)).isZero();
        assertThat(countRolesOf(tenantId)).isZero();
        assertThat(countJunction("sys_user_role", tenantId))
                .as("关联表没有 tenant_id,漏掉就留下永远查不到的孤儿行").isZero();
        assertThat(countJunction("sys_role_menu", tenantId)).isZero();
        assertThat(rowCount("select count(*) from tenant where id = ?", tenantId)).isZero();
        assertThat(countOperLogsOf(tenantId))
                .as("审计日志要留着:业务数据都没了,它是\"这个租户当时做过什么\"的唯一凭据")
                .isEqualTo(1);

        assertThat(countUsersOf(otherTenantId)).as("删错租户是灾难,不是 bug").isEqualTo(otherUsersBefore);
        assertThat(rowCount("select count(*) from tenant where id = ?", otherTenantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("还没到清理时间的不动")
    void purgeSkipsTenantsInCooldown() {
        asSuperUserRun(() -> tenantService.changeStatus(tenantId, 0));
        asSuperUserRun(() -> tenantService.close(tenantId));

        asSuperUser(tenantService::purgeExpiredTenants);

        assertThat(countUsersOf(tenantId)).as("保留期内一行都不该动").isPositive();
        assertThat(rowCount("select count(*) from tenant where id = ?", tenantId)).isEqualTo(1);
    }

    @Test
    @DisplayName("导出:每张表一个 CSV,带清单;密码哈希不导出")
    void exportContainsDataButNoCredentials() throws Exception {
        Map<String, String> files = unzip(tenantDataExporter.export(tenantId));

        assertThat(files).containsKeys("manifest.txt", "sys_user.csv", "sys_role.csv",
                "sys_user_role.csv", "sys_oper_log.csv");
        assertThat(files.get("sys_user.csv")).as("该租户的用户数据要在里面").contains(tenantUsername);
        assertThat(files.get("manifest.txt")).as("清单要能看出每张表导了多少行").contains("表,行数");
        assertThat(files.get("sys_user.csv"))
                .as("密码哈希既不能恢复、又是最敏感的数据,导出去只是给存档加一层泄露风险")
                .doesNotContain("$2a$")
                .contains("***");
    }

    // ——— 辅助 ———

    private TenantCreateResponse createTenant() {
        return asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                "purge" + suffix(), "注销用例租户" + suffix(), FULL_PACKAGE_ID, null,
                "purgeadmin" + suffix(), "用例管理员", null)));
    }

    /** 直接查库:测试库里租户很多,按分页列表找自己的那条既慢又不稳。 */
    private LocalDateTime purgeAtOf(long id) {
        java.sql.Timestamp value = jdbcTemplate.queryForObject(
                "select purge_at from tenant where id = ?", java.sql.Timestamp.class, id);
        return value == null ? null : value.toLocalDateTime();
    }

    private void backdatePurgeAt(long id) {
        jdbcTemplate.update("update tenant set purge_at = ? where id = ?",
                LocalDateTime.now().minusMinutes(1), id);
    }

    /** 造一条该租户的操作日志:purge 之后它必须还在(见下面那条断言)。 */
    private void insertOperLog(long id) {
        // id 是雪花 ID,没有库端默认值 —— 手写 SQL 造数据时必须自己给
        jdbcTemplate.update("insert into sys_oper_log (id, tenant_id, module, method, status) "
                + "values (?, ?, '注销用例', 'GET /用例', 1)", com.minimall.infra.id.SnowflakeIdGenerator.nextId(), id);
    }

    private long countOperLogsOf(long id) {
        return rowCount("select count(*) from sys_oper_log where tenant_id = ?", id);
    }

    private long countUsersOf(long id) {
        return rowCount("select count(*) from sys_user where tenant_id = ?", id);
    }

    private long countRolesOf(long id) {
        return rowCount("select count(*) from sys_role where tenant_id = ?", id);
    }

    private long countJunction(String table, long id) {
        String column = "sys_user_role".equals(table) ? "user_id" : "role_id";
        String source = "sys_user_role".equals(table) ? "sys_user" : "sys_role";
        return rowCount("select count(*) from " + table + " where " + column + " in "
                + "(select id from " + source + " where tenant_id = ?)", id);
    }

    private long rowCount(String sql, Object... args) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private Map<String, String> unzip(byte[] zipBytes) throws Exception {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                files.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, PLATFORM_ADMIN_USER_ID, "127.0.0.1", "purge-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private void asSuperUserRun(Runnable action) {
        asSuperUser(() -> {
            action.run();
            return null;
        });
    }
}
