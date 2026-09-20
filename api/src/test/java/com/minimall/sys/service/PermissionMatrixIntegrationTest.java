package com.minimall.sys.service;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import com.minimall.sys.api.dto.RoleCreateRequest;
import com.minimall.sys.api.dto.RoleMenuGrantRequest;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import com.minimall.sys.api.dto.UserSaveRequest;
import com.minimall.sys.domain.SysMenu;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.repository.SysMenuRepository;
import com.minimall.sys.domain.repository.SysUserRepository;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.security.PermissionProvider;
import com.minimall.infra.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限矩阵测试(架构文档 5.2、5.5、8.2)。
 *
 * <p>覆盖"角色 → 菜单 → 权限码"这条链上的四类结论:
 * <ol>
 *   <li><b>多角色取并集</b>:与数据权限"取最大"不同,菜单权限是"能不能做",多挂一个角色只会更能做(5.2)</li>
 *   <li><b>停用的角色不参与计算</b>:角色停用必须立即影响权限(5.5 的触发表)</li>
 *   <li><b>超管短路</b>:{@code is_super = 1} 直接拿全量启用菜单的权限码,挂什么角色都不影响(4.10)。
 *       这条的测试价值在于它**是运维事故的防线**:平台租户的角色被误改时,超管必须还能进后台</li>
 *   <li><b>没有租户上下文时默认拒绝</b>:不去猜一个租户(猜错就是把别人的权限给了当前调用者)</li>
 * </ol>
 *
 * <p>菜单 ID 取自 V2 种子数据(2=用户管理、11=用户列表 system:user:list、3=角色管理、21=角色列表
 * system:role:list),改种子数据时要同步改这里。
 *
 * <p>注意一个运维细节:{@code is_super} 不在任何接口里(4.10),它只由种子数据/运维脚本改。
 * 而权限缓存是按租户版本号失效的(5.5),**运维脚本改完 {@code is_super} 之后要清一次该租户的权限缓存**,
 * 否则在线用户可能还拿着旧的缓存值。本用例在第一次读缓存之前就把标记置好,避免依赖这一点。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class PermissionMatrixIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    private static final long FULL_PACKAGE_ID = 1L;

    private static final String USER_LIST_PERM = "system:user:list";
    private static final String ROLE_LIST_PERM = "system:role:list";
    /** 把"用户列表"这条链(父菜单 + 按钮)授给角色。 */
    private static final List<Long> USER_MENUS = List.of(1L, 2L, 11L);
    private static final List<Long> ROLE_MENUS = List.of(1L, 3L, 21L);

    @Autowired
    private TenantService tenantService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private UserService userService;
    @Autowired
    private PermissionProvider permissionProvider;
    @Autowired
    private SysMenuRepository menuRepository;
    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    @Autowired
    private org.springframework.data.redis.connection.RedisConnectionFactory redisConnectionFactory;

    private long tenantId;
    private long tenantAdminId;

    @BeforeEach
    void setUpTenant() {
        pointSaTokenDaoToCurrentContext();
        TenantCreateResponse tenant = asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                "perm-" + suffix(), "权限矩阵用例租户" + suffix(), FULL_PACKAGE_ID, null,
                "admin" + suffix(), "用例管理员", null)));
        tenantId = tenant.tenantId();
        tenantAdminId = tenant.adminUserId();
    }

    /**
     * 把 sa-token 的全局 DAO 重新指向**当前上下文**的 Redis 连接。
     *
     * <p>为什么需要:{@code SaManager} 是 JVM 全局静态的,而测试 JVM 里同时存在多个 Spring 上下文
     * (JPA 切片用例一个、事务隔离用例一个、本类一个……),每个上下文启动都会把全局 DAO 设成自己的。
     * 一旦那个上下文被关闭,它的 Lettuce 连接就停了,后面任何走全局 DAO 的调用(比如
     * {@code userService.changeStatus} 里踢会话)都会抛 {@code LettuceConnectionFactory has been STOPPED}。
     *
     * <p>生产环境只有一个上下文,不存在这个问题;这是**测试环境特有的坑**,
     * 所以修复也放在测试侧(见 9.4 的说明)。
     */
    private void pointSaTokenDaoToCurrentContext() {
        SaTokenDaoForRedisTemplate dao = new SaTokenDaoForRedisTemplate();
        dao.init(redisConnectionFactory);
        SaManager.setSaTokenDao(dao);
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("8.2:多角色菜单取并集——两个角色各自授权,用户拿到的是两边的权限码")
    void multipleRolesAreUnioned() {
        long userListRole = createRole(USER_MENUS);
        long roleListRole = createRole(ROLE_MENUS);
        long userId = createUser(List.of(userListRole, roleListRole));

        Set<String> perms = permCodesOf(userId);

        assertThat(perms).contains(USER_LIST_PERM, ROLE_LIST_PERM);
    }

    @Test
    @DisplayName("8.2:单个角色只拿到自己那一份权限,按钮权限由按钮菜单贡献")
    void singleRoleGrantsOnlyItsOwnPermissions() {
        long roleId = createRole(USER_MENUS);
        long userId = createUser(List.of(roleId));

        Set<String> perms = permCodesOf(userId);

        assertThat(perms).containsExactly(USER_LIST_PERM);
        assertThat(perms).doesNotContain(ROLE_LIST_PERM);
    }

    @Test
    @DisplayName("8.2:导航菜单只贡献 route_path、不产生权限码(前端路由与后端鉴权是两回事)")
    void navMenusContributeRoutesNotPermissions() {
        // 1=系统管理、3=角色管理:都是导航菜单,没有 perm_code
        long navOnlyRole = createRole(List.of(1L, 3L));
        long userId = createUser(List.of(navOnlyRole));

        PermissionProvider.PermissionData data = permissionDataOf(userId);

        assertThat(data.permCodes()).as("导航菜单不该产生权限码,否则前端路由会变相变成授权手段")
                .isEmpty();
        assertThat(data.menus()).contains("role");
    }

    @Test
    @DisplayName("8.2:停用的角色不参与计算——停用后它的权限立即消失")
    void disabledRoleIsExcludedImmediately() {
        long userListRole = createRole(USER_MENUS);
        long roleListRole = createRole(ROLE_MENUS);
        long userId = createUser(List.of(userListRole, roleListRole));
        assertThat(permCodesOf(userId)).contains(USER_LIST_PERM, ROLE_LIST_PERM);

        asTenant(() -> roleService.changeStatus(roleListRole, 0));

        assertThat(permCodesOf(userId)).as("角色停用必须立刻生效(5.5 的触发表)")
                .contains(USER_LIST_PERM)
                .doesNotContain(ROLE_LIST_PERM);
    }

    @Test
    @DisplayName("8.2:超管短路——拿全量启用菜单的权限码,挂什么角色、角色怎么改都不受影响")
    void superUserShortCircuitsToAllEnabledMenus() {
        long narrowRole = createRole(USER_MENUS);
        long superUserId = createUser(List.of(narrowRole));
        markAsSuperUser(superUserId);

        Set<String> allEnabledPermCodes = allEnabledPermCodes();

        assertThat(allEnabledPermCodes).as("种子菜单里必须有权限码,否则这条用例没有意义").isNotEmpty();
        assertThat(permCodesOf(superUserId)).containsExactlyInAnyOrderElementsOf(allEnabledPermCodes);

        // 把唯一那个角色清空:超管权限不该有任何变化(这是"角色被误改也能进后台"的防线)
        asTenant(() -> roleService.grantMenus(narrowRole, new RoleMenuGrantRequest(List.of())));

        assertThat(permCodesOf(superUserId))
                .as("超管不查角色,所以角色被清空也不该影响它(4.10)")
                .containsExactlyInAnyOrderElementsOf(allEnabledPermCodes);
    }

    @Test
    @DisplayName("8.2:停用用户按无权限处理——会话还在也做不了需要权限的事")
    void disabledUserHasNoPermission() {
        long roleId = createRole(USER_MENUS);
        long userId = createUser(List.of(roleId));
        assertThat(permCodesOf(userId)).contains(USER_LIST_PERM);

        asTenant(() -> userService.changeStatus(userId, 0));

        assertThat(permCodesOf(userId)).isEmpty();
    }

    @Test
    @DisplayName("8.2:没有租户上下文时返回空权限,不猜租户(猜错就是给了别人的权限)")
    void withoutTenantContextReturnsEmpty() {
        long roleId = createRole(USER_MENUS);
        long userId = createUser(List.of(roleId));

        TenantContext.clear();
        PermissionProvider.PermissionData data = permissionProvider.load(userId);

        assertThat(data.permCodes()).isEmpty();
        assertThat(data.menus()).isEmpty();
    }

    // ——— 辅助方法 ———

    private Set<String> permCodesOf(long userId) {
        return permissionDataOf(userId).permCodes();
    }

    private PermissionProvider.PermissionData permissionDataOf(long userId) {
        // 算权限要读角色的 menuIds(懒加载集合),必须在事务/Session 内:
        // 生产路径上调用方是 @Transactional 的 service 或带 OSIV 的请求,测试里显式补一个事务
        return asTenant(() -> transactionTemplate.execute(status -> permissionProvider.load(userId)));
    }

    /** 期望值从"全部启用菜单"算出来,而不是把权限码硬编码在用例里(菜单是会变的)。 */
    private Set<String> allEnabledPermCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (SysMenu menu : asSuperUser(() -> menuRepository.findAllEnabled())) {
            if (menu.getPermCode() != null && !menu.getPermCode().isBlank()) {
                codes.add(menu.getPermCode());
            }
        }
        return codes;
    }

    /**
     * 把用户标记为超管。**只能这样做**:{@code is_super} 不在任何接口里暴露(4.10),
     * 真实环境由种子数据/运维脚本设置,这里模拟那种运维动作。
     */
    private void markAsSuperUser(long userId) {
        asSuperUser(() -> {
            SysUser user = userRepository.findById(userId).orElseThrow();
            user.setIsSuper(1);
            userRepository.save(user);
            return null;
        });
    }

    private long createRole(List<Long> menuIds) {
        return asTenant(() -> {
            long roleId = roleService.create(new RoleCreateRequest("perm" + suffix(), "权限用例角色", 1, null, 1));
            if (!menuIds.isEmpty()) {
                roleService.grantMenus(roleId, new RoleMenuGrantRequest(menuIds));
            }
            return roleId;
        });
    }

    private long createUser(List<Long> roleIds) {
        String username = "permuser" + suffix();
        asTenant(() -> userService.create(
                new UserSaveRequest(username, null, "权限用例用户", null, null, roleIds, 1)));
        return asTenant(() -> userRepository.findByTenantIdAndUsername(tenantId, username).orElseThrow().getId());
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true,
                () -> bindAudit(PLATFORM_TENANT_ID, PLATFORM_ADMIN_USER_ID, action));
    }

    private <T> T asTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(tenantId, false, () -> bindAudit(tenantId, tenantAdminId, action));
    }

    private void asTenant(Runnable action) {
        asTenant(() -> {
            action.run();
            return null;
        });
    }

    private <T> T bindAudit(long boundTenantId, long userId, Supplier<T> action) {
        AuditContext.bind(new AuditContext(boundTenantId, userId, "127.0.0.1", "perm-it"));
        try {
            return action.get();
        } finally {
            AuditContext.clear();
        }
    }
}
