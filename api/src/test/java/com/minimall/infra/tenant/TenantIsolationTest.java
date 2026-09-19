package com.minimall.infra.tenant;

import com.minimall.sys.domain.SysUser;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.config.JpaAuditingConfig;
import com.minimall.support.JpaTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户隔离行为验证(架构文档 8.1 用例 1、2、6、7)。
 *
 * <p><b>为什么这条用例最重要</b>:它是唯一能证明"TenantContext 里租户设对了、Hibernate 层也真的生效了"的用例。
 * 如果 {@code TenantFilterService} 没被调用、或者 enable 的时机不对,实体查起来是"全部可见",
 * 而其他所有单测都会继续通过 —— 这正是 4.2 里强调"最容易被实现者忽略"的那个点。
 *
 * <p>它不经过 HTTP(手动调 {@code TenantFilterService.apply}),对应 8.1 用例 6 的异步场景模拟。
 * 注意超管场景的断言方式:超管**不启用**过滤器,而不是"传一个特殊值匹配",见 4.2。
 *
 * <p>依赖真实 MySQL(关闭内存库替换),所以打 {@code integration} 标签;
 * 数据权限这一层用一个"全部可见"的桩 provider 隔离掉,本用例只验证租户维度。
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("dev")
@Import({TenantFilterService.class, TenantIsolationTest.ScopeStubConfig.class,
        JpaAuditingConfig.class, JpaTestSupport.class})
class TenantIsolationTest {

    private static final long TENANT_A = 101L;
    private static final long TENANT_B = 102L;

    @PersistenceContext
    private EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    private TenantFilterService tenantFilterService;

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("用例1/6:租户 A 的上下文里只能查到 A 的数据,看不到 B 的")
    void tenantOnlySeesItsOwnRows() {
        persistUser(TENANT_A, "a_owner");
        persistUser(TENANT_B, "b_owner");

        List<String> visibleInA = usernamesVisibleAs(TENANT_A, false);

        assertThat(visibleInA).containsExactly("a_owner");
    }

    @Test
    @DisplayName("用例2:超管上下文能看到所有租户的数据(过滤器不启用,不是传特殊值)")
    void superUserSeesAllTenants() {
        persistUser(TENANT_A, "a_owner");
        persistUser(TENANT_B, "b_owner");

        List<String> visible = usernamesVisibleAs(TENANT_B, true);

        assertThat(visible).contains("a_owner", "b_owner");
    }

    @Test
    @DisplayName("用例7:未定租户时默认拒绝——返回空集,而不是全表")
    void unboundTenantSeesNothing() {
        persistUser(TENANT_A, "a_owner");

        entityManager.flush();
        entityManager.clear();

        TenantContext.clear();
        tenantFilterService.apply(entityManager);

        List<String> visible = entityManager
                .createQuery("select u.username from SysUser u where u.username in ('a_owner')", String.class)
                .getResultList();

        assertThat(visible).isEmpty();
    }

    @Test
    @DisplayName("tenant_id 自动回填:不显式设置时按当前上下文落库,且 create_by 来自审计快照")
    void tenantIdAndOwnerAreFilledFromContexts() {
        persistUser(TENANT_A, "a_auto");

        entityManager.flush();
        entityManager.clear();

        TenantContext.runAsTenant(TENANT_A, false, () -> {
            tenantFilterService.apply(entityManager);
            SysUser loaded = entityManager
                    .createQuery("select u from SysUser u where u.username = 'a_auto'", SysUser.class)
                    .getSingleResult();
            assertThat(loaded.getTenantId()).isEqualTo(TENANT_A);
            assertThat(loaded.getCreateBy()).isEqualTo(9001L);
        });
    }

    private List<String> usernamesVisibleAs(long tenantId, boolean superUser) {
        return TenantContext.callAsTenant(tenantId, superUser, () -> {
            tenantFilterService.apply(entityManager);
            return entityManager.createQuery(
                            "select u.username from SysUser u where u.username in ('a_owner','b_owner')", String.class)
                    .getResultList();
        });
    }

    /**
     * 直接持久化一条用户数据。SysUser 标注了 OwnedEntity,所以 create_by 必须非空(5.3),
     * 这里通过绑定审计快照来满足,模拟"有请求周期"的写入。
     */
    private void persistUser(long tenantId, String username) {
        TenantContext.runAsTenant(tenantId, false, () -> {
            AuditContext.bind(new AuditContext(tenantId, 9001L, "127.0.0.1", "test-trace"));
            SysUser user = new SysUser();
            user.setUsername(username);
            user.setPassword("$2a$10$not-a-real-hash-for-test-only");
            user.setStatus(1);
            user.setIsSuper(0);
            user.setMustChangePassword(0);
            user.setLoginFailCount(0);
            entityManager.persist(user);
        });
    }

    /**
     * 数据权限的桩:返回"全部可见",把数据权限这一层隔离掉。
     * 真实实现(按角色算 dataScope)在 service 侧,后续单独用 8.3 的用例覆盖。
     */
    @TestConfiguration
    static class ScopeStubConfig {

        @Bean
        DataScopeProvider scopeAllProvider() {
            return () -> new DataScopeParams(DataScopeParams.SCOPE_ALL, null, null, null);
        }
    }
}
