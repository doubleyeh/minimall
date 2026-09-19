package com.minimall.sys.service;

import com.minimall.sys.api.SysUserController;
import com.minimall.sys.api.dto.RoleCreateRequest;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import com.minimall.sys.api.dto.UserSaveRequest;
import com.minimall.sys.domain.SysOperLog;
import com.minimall.sys.domain.repository.SysOperLogRepository;
import com.minimall.sys.domain.repository.SysRoleRepository;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.infra.web.TraceIdFilter;
import com.minimall.sys.service.support.TenantTaskRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 脱离 HTTP 请求周期的场景(架构文档 6.2、6.3、8.4)。
 *
 * <p>覆盖三件事:
 * <ol>
 *   <li><b>逐租户任务的失败隔离</b>:一个租户失败不影响其他租户,且每个租户各自独立提交事务
 *       ——"独立事务"这条用"失败租户的写入被回滚、其他租户的写入已提交"来证伪,
 *       光断言"没抛异常"是测不出共用一个事务的</li>
 *   <li><b>事务内调用逐租户任务会被拒绝</b>:否则所有租户挤在一个事务里,单租户失败会把整批带回滚</li>
 *   <li><b>异步落库用的是提交时的身份快照</b>:断言异步线程写入的 {@code tenant_id}/{@code user_id}
 *       与提交任务时一致 —— 异步线程里没有 Sa-Token 会话,一旦靠"线程内推断"就会写成 null 或别人</li>
 * </ol>
 *
 * <p>本类**不加** {@code @Transactional}:第 1、2 条测的就是"调用方有没有事务"这件事。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("dev")
@Import(AsyncScenarioIntegrationTest.TaskConfig.class)
class AsyncScenarioIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    private static final long FULL_PACKAGE_ID = 1L;

    @Autowired
    private TenantTaskRunner tenantTaskRunner;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private UserService userService;
    @Autowired
    private SysRoleRepository roleRepository;
    @Autowired
    private com.minimall.sys.domain.repository.SysUserRepository userRepository;
    @Autowired
    private SysOperLogRepository operLogRepository;
    @Autowired
    private SysUserController userController;
    @Autowired
    private TenantWriteTask tenantWriteTask;

    @AfterEach
    void clearContexts() {
        MDC.clear();
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("用例8.4:一个租户处理失败不影响其他租户,且每个租户各自独立提交事务")
    void oneTenantFailureDoesNotBreakTheBatch() {
        TenantCreateResponse tenantA = createTenant();
        TenantCreateResponse tenantB = createTenant();
        String roleKey = "taskrole" + suffix();

        TenantTaskRunner.RunResult result = tenantTaskRunner.runForEachTenant(
                "集成测试逐租户任务", PLATFORM_ADMIN_USER_ID,
                // 每个租户跑同一个事务方法:tenantB 会在写入之后抛异常
                tenantId -> tenantWriteTask.run(tenantId, roleKey, tenantId.equals(tenantB.tenantId())));

        assertThat(result.allSucceeded()).isFalse();
        assertThat(result.failedTenantIds()).containsExactly(tenantB.tenantId());

        assertThat(roleExists(tenantB.tenantId(), roleKey))
                .as("失败租户必须整租户回滚,不能留下半截数据")
                .isFalse();
        assertThat(roleExists(tenantA.tenantId(), roleKey))
                .as("其他租户的写入必须已经提交:各自独立事务,不因别人失败而回滚")
                .isTrue();
    }

    @Test
    @DisplayName("用例8.4:在事务内调用逐租户任务会被直接拒绝")
    void refusesToRunInsideAmbientTransaction() {
        assertThatThrownBy(() -> tenantWriteTask.inTransaction(() ->
                tenantTaskRunner.runForEachTenant("事务内调用", PLATFORM_ADMIN_USER_ID, tenantId -> { })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须在事务外调用");
    }

    @Test
    @DisplayName("用例8.4:异步落库的 tenant_id/user_id 来自提交时的快照,而不是异步线程里的推断")
    void asyncOperLogKeepsSubmitTimeIdentity() {
        TenantCreateResponse tenant = createTenant();
        long targetUserId = createUser(tenant, "logtarget");
        String traceId = "it-asynclog-" + suffix();
        // 审计切面从 MDC 取 traceId、从 AuditContext 取租户与操作人(7.2、4.12)
        long actorUserId = tenant.adminUserId();

        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, traceId);
        try {
            TenantContext.callAsTenant(tenant.tenantId(), true, () -> {
                AuditContext.bind(new AuditContext(tenant.tenantId(), actorUserId, "127.0.0.1", traceId));
                try {
                    // 直接调用带 @AuditLog 的接口方法(不经过 MVC,等价于一次真实请求的业务部分)
                    userController.update(targetUserId, new UserSaveRequest(
                            "logtarget" + suffix(), null, "异步日志用例改名", null, null, null, 1));
                } finally {
                    AuditContext.clear();
                }
                return null;
            });
        } finally {
            MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
        }

        SysOperLog row = awaitOperLog(tenant.tenantId(), traceId);

        assertThat(row.getTenantId()).isEqualTo(tenant.tenantId());
        assertThat(row.getUserId()).as("异步线程里没有会话,身份只能来自提交时的快照").isEqualTo(actorUserId);
        assertThat(row.getPermCode()).isEqualTo("system:user:update");
        assertThat(row.getTraceId()).isEqualTo(traceId);
        assertThat(row.getStatus()).isEqualTo(1);
    }

    // ——— 辅助方法 ———

    /** 异步落库意味着"接口已返回、日志可能还没写进去",只能轮询等它落地。 */
    private SysOperLog awaitOperLog(long tenantId, String traceId) {
        for (int i = 0; i < 50; i++) {
            Optional<SysOperLog> row = TenantContext.callAsTenant(tenantId, true,
                    () -> operLogRepository.findFirstByTraceIdOrderByIdDesc(traceId));
            if (row.isPresent()) {
                return row.get();
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待操作日志落库时被中断", ex);
            }
        }
        throw new AssertionError("操作日志在 5 秒内没有落库:traceId=" + traceId);
    }

    /** 用超管上下文读:断言的是"数据到底写没写",不是"过滤有没有生效"。 */
    private boolean roleExists(long tenantId, String roleKey) {
        return TenantContext.callAsTenant(tenantId, true,
                () -> roleRepository.findByTenantIdAndRoleKey(tenantId, roleKey).isPresent());
    }

    private TenantCreateResponse createTenant() {
        return asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                "async-" + suffix(), "异步用例租户" + suffix(), FULL_PACKAGE_ID, null,
                "admin" + suffix(), "用例管理员", null)));
    }

    private long createUser(TenantCreateResponse tenant, String namePrefix) {
        String username = namePrefix + suffix();
        // 以该租户管理员的身份创建(它不是超管,走的是租户内正常写入路径)
        return TenantContext.callAsTenant(tenant.tenantId(), false, () -> {
            AuditContext.bind(new AuditContext(tenant.tenantId(), tenant.adminUserId(), "127.0.0.1", "async-it"));
            try {
                userService.create(new UserSaveRequest(username, null, "异步用例用户", null, null, null, 1));
                return userRepository.findByTenantIdAndUsername(tenant.tenantId(), username).orElseThrow().getId();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, PLATFORM_ADMIN_USER_ID, "127.0.0.1", "async-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    /**
     * 模拟"单个租户的任务方法":一个事务里做完本次租户的全部写入,失败时整租户回滚。
     *
     * <p>放在测试里而不是产品代码:这一版没有真实的定时业务任务(不做支付与商品),
     * 但 6.2 要求的形式必须能被验证 —— 异步入口方法要标注 {@code @Transactional}。
     */
    static class TenantWriteTask {

        private final RoleService roleService;

        TenantWriteTask(RoleService roleService) {
            this.roleService = roleService;
        }

        @Transactional
        public void run(Long tenantId, String roleKey, boolean fail) {
            // 与调用方共用同一个事务(REQUIRED):所以下面的异常会把这次写入一起回滚
            roleService.create(new RoleCreateRequest(roleKey, "任务写入角色", 1, null, 1));
            if (fail) {
                throw new IllegalStateException("模拟该租户处理失败 tenantId=" + tenantId);
            }
        }

        @Transactional
        public void inTransaction(Runnable action) {
            action.run();
        }
    }

    @TestConfiguration
    static class TaskConfig {

        @Bean
        TenantWriteTask tenantWriteTask(RoleService roleService) {
            return new TenantWriteTask(roleService);
        }
    }
}
