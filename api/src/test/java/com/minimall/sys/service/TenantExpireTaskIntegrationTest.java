package com.minimall.sys.service;

import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.security.RefreshTokenStore;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.infra.tenant.TenantLookup;
import com.minimall.infra.tenant.TenantSnapshot;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户到期处理(架构文档 6.2、4.11)。
 *
 * <p>补的是"没人主动处理"这个缺口:有效期此前只在用户请求时被动校验,库里状态一直停在
 * "正常" —— 看租户列表会以为一切正常,实际早已过期。
 *
 * <p>断言只针对本用例自己造的租户:测试库里还有别的用例建的租户,它们都是未来到期,
 * 所以"一共禁用了几个"这种全量断言会随别的用例变化而碎。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class TenantExpireTaskIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    private static final long FULL_PACKAGE_ID = 1L;

    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantLookup tenantLookup;
    @Autowired
    private RefreshTokenStore refreshTokenStore;

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("到期任务:已过期的置为禁用,未到期的保持正常;重跑不再重复处理")
    void disablesOnlyExpiredTenants() {
        long expired = createTenant(LocalDateTime.now().minusDays(1)).tenantId();
        long expiringSoon = createTenant(LocalDateTime.now().plusDays(3)).tenantId();
        long farFuture = createTenant(LocalDateTime.now().plusDays(30)).tenantId();

        int firstRun = runTask();

        assertThat(firstRun).as("本次至少处理了刚造的那一个过期租户").isGreaterThanOrEqualTo(1);
        assertThat(statusOf(expired)).as("过期租户要被主动禁用,不能一直停在正常").isZero();
        assertThat(statusOf(expiringSoon)).as("还没到期的不动").isEqualTo(1);
        assertThat(statusOf(farFuture)).isEqualTo(1);

        assertThat(runTask())
                .as("幂等:只挑 status=1 的,重跑不会再处理一遍")
                .isZero();
    }

    @Test
    @DisplayName("禁用到期租户时一并撤销其刷新令牌——任务线程没有租户上下文,这里最容易静默失效")
    void revokesRefreshTokensOfExpiredTenant() {
        TenantCreateResponse tenant = createTenant(LocalDateTime.now().minusHours(1));
        refreshTokenStore.addToUserIndex(tenant.adminUserId(), "tok-" + suffix(), Duration.ofHours(1));
        assertThat(refreshTokenStore.tokensOf(tenant.adminUserId())).isNotEmpty();

        runTask();

        assertThat(refreshTokenStore.tokensOf(tenant.adminUserId()))
                .as("没切到该租户的上下文,查它的用户会被过滤器绑哨兵拦住 → 一条都没撤,状态却已禁用")
                .isEmpty();
    }

    @Test
    @DisplayName("到期提醒:只列出提醒窗口内到期的启用中租户")
    void listsTenantsExpiringSoon() {
        long expiringSoon = createTenant(LocalDateTime.now().plusDays(3)).tenantId();
        createTenant(LocalDateTime.now().plusDays(30));

        LocalDateTime now = LocalDateTime.now();
        // 同样按调度器的路径调:线程里没有上下文,而 tenant 表本身不挂过滤器,不该受影响
        List<TenantSnapshot> expiring = tenantService.listExpiringBetween(now, now.plusDays(7));

        assertThat(expiring).extracting(TenantSnapshot::id).contains(expiringSoon);
        assertThat(expiring).extracting(TenantSnapshot::id).doesNotContain(PLATFORM_TENANT_ID);
        assertThat(expiring).allSatisfy(snapshot ->
                assertThat(snapshot.expireTime()).isAfter(now).isBefore(now.plusDays(7).plusMinutes(1)));
    }

    @Test
    @DisplayName("禁用后租户缓存同步失效:不会在 TTL 内还按\"正常\"放行")
    void cacheIsEvictedAfterDisabling() {
        long expired = createTenant(LocalDateTime.now().minusHours(1)).tenantId();
        asSuperUser(() -> tenantLookup.byId(expired)).orElseThrow();

        runTask();

        assertThat(asSuperUser(() -> tenantLookup.byId(expired).orElseThrow().usable()))
                .as("缓存里还留着\"正常\"的话,该租户用户到 TTL 之前都还能继续用")
                .isFalse();
    }

    /** 与调度器同一条路径:线程里没有任何租户/审计上下文。 */
    private int runTask() {
        return tenantService.disableExpiredTenants();
    }

    private TenantCreateResponse createTenant(LocalDateTime expireTime) {
        return asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                "exp-" + suffix(), "到期任务用例租户" + suffix(), FULL_PACKAGE_ID, expireTime,
                "admin" + suffix(), "用例管理员", null)));
    }

    private int statusOf(long tenantId) {
        return asSuperUser(() -> tenantLookup.byId(tenantId).orElseThrow().status());
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, PLATFORM_ADMIN_USER_ID, "127.0.0.1", "exp-task-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }
}
