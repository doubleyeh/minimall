package com.minimall.sys.service;

import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.api.dto.PackageMenuSaveRequest;
import com.minimall.sys.api.dto.PackageSaveRequest;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.service.impl.PackageMenuSyncService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * 套餐菜单同步的**失败隔离**(架构文档 4.8.1 步骤 5)。
 *
 * <p>这里要钉住的保证是:一次套餐变更要落到所有绑定它的租户身上,而**单个租户失败不能拖住其他租户** ——
 * 失败只记录、不中断、不回滚已成功的租户(失败的租户停在未同步状态,可由 {@code resync} 重跑)。
 *
 * <p><b>为什么用 mock 而不是造一个真的会失败的租户</b>:要让真实同步失败,得先把某个租户的数据
 * 弄成不一致(删它的默认角色之类),那既难构造、又会污染这个租户的其它断言。
 * 而这条保证的关键恰恰是"服务层抛异常时编排者怎么做",与失败的具体原因无关 ——
 * 把 {@code PackageMenuSyncService} 换成抛异常的替身,正好只测这一件事。
 *
 * <p>用 {@code @MockitoBean} 会让本类拥有独立的 Spring 上下文(与其它类不共享),这是它的代价;
 * 换来的是不必为了覆盖三行 catch 去构造一个坏租户。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackageMenuSyncIsolationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long SEED_ADMIN_ID = 1L;
    /** 种子菜单:1=系统管理(目录)、2=用户管理(页面)。父链完整才能进套餐。 */
    private static final List<Long> MENUS = List.of(1L, 2L);

    @Autowired
    private PackageService packageService;
    @Autowired
    private TenantService tenantService;

    @MockitoBean
    private PackageMenuSyncService syncService;

    private Long packageId;
    private Long firstTenantId;
    private Long secondTenantId;

    @BeforeAll
    void createPackageAndTwoTenants() {
        packageId = asSuperUser(() -> packageService.create(new PackageSaveRequest("孤立性套餐" + suffix(), "用例", 1)));
        firstTenantId = createTenant();
        secondTenantId = createTenant();
    }

    @Test
    @DisplayName("套餐菜单变更:一个租户同步失败,其余租户仍然被同步,且整体不抛异常")
    void oneFailingTenantDoesNotStopTheOthers() {
        Mockito.doThrow(new IllegalStateException("模拟某个租户同步失败"))
                .when(syncService).syncTenant(eq(firstTenantId), eq(packageId), anySet(), anySet(), anyInt());

        assertThatCode(() -> asSuperUser(() -> {
            packageService.saveMenus(packageId, new PackageMenuSaveRequest(MENUS));
            return null;
        }))
                .as("""
                        单个租户失败不能把整个保存动作变成失败 —— 保存已经落库了(套餐的菜单集合先改),
                        这时抛异常会让管理端以为没保存成功,而后台其实改了一半。
                        正确做法是记下失败的租户,由 resync 重跑。""")
                .doesNotThrowAnyException();

        // 第二个租户必须仍然被同步:循环不能被第一个租户的异常打断
        verify(syncService).syncTenant(eq(secondTenantId), eq(packageId), anySet(), anySet(), anyInt());
        // 第一个租户也确实被尝试过
        verify(syncService).syncTenant(eq(firstTenantId), eq(packageId), anySet(), anySet(), anyInt());
    }

    @Test
    @DisplayName("重新同步:个别租户失败只记录,方法本身不抛出")
    void resyncReportsFailuresWithoutThrowing() {
        Mockito.doThrow(new IllegalStateException("模拟收敛失败"))
                .when(syncService).convergeTenant(eq(secondTenantId), eq(packageId), anySet());

        assertThatCode(() -> asSuperUser(() -> {
            packageService.resync(packageId);
            return null;
        }))
                .as("resync 是运维手段,失败要能重跑;它自己抛异常会让调用方失去继续处理其他租户的机会")
                .doesNotThrowAnyException();

        verify(syncService).convergeTenant(eq(firstTenantId), eq(packageId), anySet());
        verify(syncService).convergeTenant(eq(secondTenantId), eq(packageId), anySet());
    }

    @Test
    @DisplayName("传空数组表示清空套餐菜单:这是合法操作,不能被当成参数错误")
    void savingEmptyMenuListClearsPackageMenus() {
        asSuperUser(() -> {
            packageService.saveMenus(packageId, new PackageMenuSaveRequest(MENUS));
            return null;
        });
        assertThat(asSuperUser(() -> packageService.menuIds(packageId))).containsExactlyElementsOf(MENUS);

        asSuperUser(() -> {
            // PackageMenuSaveRequest 的说明里写明:清空套餐菜单请传空数组
            packageService.saveMenus(packageId, new PackageMenuSaveRequest(List.of()));
            return null;
        });

        assertThat(asSuperUser(() -> packageService.menuIds(packageId)))
                .as("空数组是清空语义,不是参数错误")
                .isEmpty();
        // 清空同样要通知到租户(全部菜单都被回收)
        verify(syncService).syncTenant(eq(firstTenantId), eq(packageId), anySet(), eq(Set.of(1L, 2L)), anyInt());
    }

    // ---------------------------------------------------------------- 辅助

    private Long createTenant() {
        String username = "syncadmin" + suffix();
        return asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                "sync-" + suffix(), "同步孤立性用例租户", packageId, null,
                username, "用例管理员", null))).tenantId();
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, SEED_ADMIN_ID, "127.0.0.1", "pkg-sync-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }
}
