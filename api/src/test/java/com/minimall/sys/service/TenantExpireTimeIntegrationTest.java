package com.minimall.sys.service;

import com.minimall.common.BusinessException;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.security.RefreshTokenStore;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.infra.tenant.TenantLookup;
import com.minimall.infra.tenant.TenantSnapshot;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户有效期的修改与生效(架构文档 4.11)。
 *
 * <p>有效期此前只在建租户时能填、建完没有入口,但它一直在被校验(TenantSnapshot.usable),
 * 所以缺的是入口而不是能力。本用例钉住两点:
 * <ol>
 *   <li><b>改完立刻生效</b>:读完一次缓存(把旧值放进 Redis)再改,随后必须读到新值 ——
 *       漏了 evict 就会在 TTL 内继续用旧有效期放行</li>
 *   <li><b>改成已过去的时间等价于立刻禁用</b>:并且要撤销刷新令牌,不能只把库改一改</li>
 * </ol>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class TenantExpireTimeIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    private static final long FULL_PACKAGE_ID = 1L;

    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantLookup tenantLookup;
    @Autowired
    private RefreshTokenStore refreshTokenStore;

    private long tenantId;
    private long tenantAdminId;

    @BeforeEach
    void setUpTenant() {
        TenantCreateResponse tenant = asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                "expire-" + suffix(), "有效期用例租户" + suffix(), FULL_PACKAGE_ID, null,
                "admin" + suffix(), "用例管理员", null)));
        tenantId = tenant.tenantId();
        tenantAdminId = tenant.adminUserId();
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("改有效期:立刻生效,不依赖缓存 TTL 自然过期")
    void changeTakesEffectImmediately() {
        // 先读一次,把"不过期"的旧快照放进 Redis
        assertThat(asSuperUser(() -> tenantLookup.byId(tenantId).orElseThrow().expireTime())).isNull();

        LocalDateTime future = LocalDateTime.now().plusDays(30).withNano(0);
        asSuperUserRun(() -> tenantService.changeExpireTime(tenantId, future));

        assertThat(asSuperUser(() -> tenantLookup.byId(tenantId).orElseThrow().expireTime()))
                .as("漏了 evict 的话这里读到的还是缓存里的旧值(空),要等 TTL 才更新")
                .isEqualTo(future);
    }

    @Test
    @DisplayName("有效期可以改回不过期(传空)")
    void canBeCleared() {
        asSuperUserRun(() -> tenantService.changeExpireTime(tenantId, LocalDateTime.now().plusDays(1)));
        assertThat(asSuperUser(() -> tenantLookup.byId(tenantId).orElseThrow().usable())).isTrue();

        asSuperUserRun(() -> tenantService.changeExpireTime(tenantId, null));

        TenantSnapshot snapshot = asSuperUser(() -> tenantLookup.byId(tenantId).orElseThrow());
        assertThat(snapshot.expireTime()).isNull();
        assertThat(snapshot.usable()).isTrue();
    }

    @Test
    @DisplayName("改成已过去的时间等价于立刻禁用:租户不可用,且刷新令牌被撤销")
    void pastExpireTimeDisablesTenant() {
        // 造一个"该租户下有人在线"的状态
        String token = "it-token-" + suffix();
        refreshTokenStore.addToUserIndex(tenantAdminId, token, Duration.ofHours(1));
        assertThat(refreshTokenStore.tokensOf(tenantAdminId)).isNotEmpty();

        asSuperUserRun(() -> tenantService.changeExpireTime(tenantId, LocalDateTime.now().minusMinutes(1)));

        assertThat(asSuperUser(() -> tenantLookup.byId(tenantId).orElseThrow().usable()))
                .as("过期租户按不可用处理,下一次请求就会被拦下")
                .isFalse();
        assertThat(refreshTokenStore.tokensOf(tenantAdminId))
                .as("过期后不该留着刷新凭据,否则用户还能拿它换新令牌")
                .isEmpty();
    }

    @Test
    @DisplayName("租户不存在时报 404 业务码,不是静默成功")
    void rejectsUnknownTenant() {
        assertThatThrownBy(() -> asSuperUserRun(
                () -> tenantService.changeExpireTime(999999999L, LocalDateTime.now().plusDays(1))))
                .isInstanceOf(BusinessException.class);
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true,
                () -> bindAudit(action));
    }

    /** 返回 void 的动作走这个重载,省得每处都写 `return null`。 */
    private void asSuperUserRun(Runnable action) {
        asSuperUser(() -> {
            action.run();
            return null;
        });
    }

    private <T> T bindAudit(Supplier<T> action) {
        AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, PLATFORM_ADMIN_USER_ID, "127.0.0.1", "expire-it"));
        try {
            return action.get();
        } finally {
            AuditContext.clear();
        }
    }
}
