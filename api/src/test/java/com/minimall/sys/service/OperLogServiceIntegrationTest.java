package com.minimall.sys.service;

import com.minimall.common.BusinessException;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.api.dto.OperLogQuery;
import com.minimall.sys.api.dto.OperLogView;
import com.minimall.sys.domain.SysOperLog;
import com.minimall.sys.domain.repository.SysOperLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 操作日志查询验证(架构文档 7.2)。
 *
 * <p>这个模块此前只有写入没有读取(表与异步写入器都在,但没有查询接口与页面),
 * 所以本用例的重点是**查询本身可用**:条件过滤、时间范围、分页与排序,以及租户隔离仍然生效 ——
 * 审计日志一旦串租户,比没有日志更糟。
 *
 * <p>日志直接落库而不是走 {@code @AuditLog} 切面:切面是异步写(靠 traceId 回捞),
 * 用它来造数据会让断言变成"等异步线程",不确定且慢。写入路径另有 {@code AsyncScenarioIntegrationTest} 覆盖。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class OperLogServiceIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long OTHER_TENANT_ID = 2L;

    @Autowired
    private OperLogService operLogService;
    @Autowired
    private SysOperLogRepository operLogRepository;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("按模块模糊匹配与结果过滤,并按时间倒序返回")
    void filtersByModuleAndStatus() {
        String module = "用例模块" + System.nanoTime();
        saveLog(PLATFORM_TENANT_ID, module, 1, null, LocalDateTime.now().minusMinutes(2));
        saveLog(PLATFORM_TENANT_ID, module, 0, "炸了", LocalDateTime.now().minusMinutes(1));

        PageResult<OperLogView> all = asTenant(PLATFORM_TENANT_ID, () -> operLogService.page(
                new OperLogQuery(null, null, module, null, null, null, 1, 10)));
        PageResult<OperLogView> failed = asTenant(PLATFORM_TENANT_ID, () -> operLogService.page(
                new OperLogQuery(null, null, module, 0, null, null, 1, 10)));

        assertThat(all.total()).isEqualTo(2);
        assertThat(all.list()).extracting(OperLogView::createTime)
                .as("按时间倒序,最近的在前面")
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(failed.total()).isEqualTo(1);
        assertThat(failed.list().get(0).errorMsg()).isEqualTo("炸了");
    }

    @Test
    @DisplayName("时间范围过滤:区间外的日志不返回")
    void filtersByTimeRange() {
        String module = "用例时间" + System.nanoTime();
        LocalDateTime base = LocalDateTime.now().minusHours(3);
        saveLog(PLATFORM_TENANT_ID, module, 1, null, base);
        saveLog(PLATFORM_TENANT_ID, module, 1, null, base.plusHours(1));
        saveLog(PLATFORM_TENANT_ID, module, 1, null, base.plusHours(2));

        PageResult<OperLogView> page = asTenant(PLATFORM_TENANT_ID, () -> operLogService.page(
                new OperLogQuery(null, null, module, null,
                        base.plusMinutes(30), base.plusHours(1).plusMinutes(30), 1, 10)));

        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("分页:第二页只返回剩下的那条")
    void paginates() {
        String module = "用例分页" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            saveLog(PLATFORM_TENANT_ID, module, 1, null, LocalDateTime.now().minusMinutes(i + 1L));
        }

        PageResult<OperLogView> first = asTenant(PLATFORM_TENANT_ID, () -> operLogService.page(
                new OperLogQuery(null, null, module, null, null, null, 1, 2)));
        PageResult<OperLogView> second = asTenant(PLATFORM_TENANT_ID, () -> operLogService.page(
                new OperLogQuery(null, null, module, null, null, null, 2, 2)));

        assertThat(first.total()).isEqualTo(3);
        assertThat(first.list()).hasSize(2);
        assertThat(second.list()).as("第二页只剩一条,且不是第一页的重复").hasSize(1);
        assertThat(second.list().get(0).id()).isNotIn(first.list().stream().map(OperLogView::id).toList());
    }

    @Test
    @DisplayName("租户隔离:查不到别的租户的日志——审计日志串租户比没有日志更糟")
    void doesNotLeakAcrossTenants() {
        String module = "用例隔离" + System.nanoTime();
        saveLog(PLATFORM_TENANT_ID, module, 1, null, LocalDateTime.now());
        saveLog(OTHER_TENANT_ID, module, 1, null, LocalDateTime.now());

        PageResult<OperLogView> ofPlatform = asTenant(PLATFORM_TENANT_ID, () -> operLogService.page(
                new OperLogQuery(null, null, module, null, null, null, 1, 10)));
        PageResult<OperLogView> ofOther = asTenant(OTHER_TENANT_ID, () -> operLogService.page(
                new OperLogQuery(null, null, module, null, null, null, 1, 10)));

        assertThat(ofPlatform.total()).as("平台租户只看到自己那条").isEqualTo(1);
        assertThat(ofPlatform.list().get(0).tenantId()).isEqualTo(PLATFORM_TENANT_ID);
        assertThat(ofOther.total()).isEqualTo(1);
        assertThat(ofOther.list().get(0).tenantId()).isEqualTo(OTHER_TENANT_ID);
    }

    @Test
    @DisplayName("导出上限:超过就报错让调用方缩小范围,不静默截断")
    void exportRefusesWhenOverLimit() {
        String module = "用例导出" + System.nanoTime();
        for (int i = 0; i < 3; i++) {
            saveLog(PLATFORM_TENANT_ID, module, 1, null, LocalDateTime.now().minusMinutes(i + 1L));
        }

        OperLogQuery query = new OperLogQuery(null, null, module, null, null, null, 1, 10);

        assertThat(asTenant(PLATFORM_TENANT_ID, () -> operLogService.listForExport(query, 5)))
                .as("上限之内正常返回").hasSize(3);
        assertThatThrownBy(() -> asTenant(PLATFORM_TENANT_ID, () -> operLogService.listForExport(query, 2)))
                .as("静默截断的导出最危险:拿到的人会以为\"就这么多\"")
                .isInstanceOf(BusinessException.class);
    }

    /** 写入方必须显式给 tenant_id/user_id:异步线程里没有租户上下文(见仓储注释)。 */
    private void saveLog(long tenantId, String module, int status, String errorMsg, LocalDateTime createTime) {
        Long id = asTenant(tenantId, () -> {
            SysOperLog log = new SysOperLog();
            log.setTenantId(tenantId);
            log.setUserId(1L);
            log.setModule(module);
            log.setPermCode("system:user:list");
            log.setMethod("GET /system/users");
            log.setRequestParams("{\"pageNo\":1}");
            log.setStatus(status);
            log.setErrorMsg(errorMsg);
            log.setIp("127.0.0.1");
            log.setTraceId("trace-" + System.nanoTime());
            return operLogRepository.save(log).getId();
        });
        // create_time 是 @CreatedDate + updatable=false,插入时由审计监听器写成当前时间。
        // 要造"历史日志"只能绕过 JPA 直接改库(与订单超时用例同样的手法)。
        jdbcTemplate.update("UPDATE sys_oper_log SET create_time = ? WHERE id = ?", createTime, id);
    }

    private <T> T asTenant(long tenantId, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantId, false, () -> {
            AuditContext.bind(new AuditContext(tenantId, 1L, "127.0.0.1", "oper-log-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }
}
