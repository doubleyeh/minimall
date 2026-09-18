package com.minimall.service.sys;

import com.minimall.api.sys.dto.DeptSaveRequest;
import com.minimall.api.sys.dto.RoleCreateRequest;
import com.minimall.api.sys.dto.RoleMenuGrantRequest;
import com.minimall.api.sys.dto.TenantCreateRequest;
import com.minimall.api.sys.dto.TenantCreateResponse;
import com.minimall.api.sys.dto.UserSaveRequest;
import com.minimall.api.sys.dto.UserView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.domain.sys.repository.SysUserRepository;
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

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据权限矩阵与越权防护的端到端用例(架构文档 5.3、7.3、5.5、8.1 用例 3、4、5、8、17)。
 *
 * <p><b>为什么必须用真实的 {@code DataScopeProvider}</b>:这里的每一个断言都是"角色档位 →
 * 实际能查到哪些行"的映射。用桩 provider 只能验证过滤器挂没挂上,验证不了档位算得对不对;
 * 而档位算错的后果是**越权**(看到别人的数据),不是报错,所以必须用真角色真部门跑一遍。
 *
 * <p>断言全部落在 {@link UserService#page} 的返回结果上(即"接口实际能看到什么"),而不是仓储层,
 * 这样连"服务层又手写了一遍部门条件"这类问题也能一起兜住。
 *
 * <p>每个用例在 {@code @BeforeEach} 里现建一个租户,租户之间天然隔离(4.1),
 * 用例之间不共享数据、也不用清理(测试库是一次性的,见 9.4)。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("dev")
class DataScopeIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    private static final long FULL_PACKAGE_ID = 1L;
    /** "系统管理 → 用户管理 → 用户列表"这条最小授权链(5.2.1 要求父链完整)。 */
    private static final List<Long> USER_LIST_MENUS = List.of(1L, 2L, 11L);
    private static final String USER_LIST_PERM = "system:user:list";

    @Autowired
    private TenantService tenantService;
    @Autowired
    private DeptService deptService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private UserService userService;
    @Autowired
    private PermissionProvider permissionProvider;
    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private long tenantId;
    /** 该租户的管理员:档5 + 由建租户流程创建,后续所有 setup 都以它的身份执行。 */
    private long tenantAdminId;
    private long deptA;
    private long deptAChild;
    private long deptB;
    private long roleSelf;
    private long roleDept;
    private long roleDeptBelow;
    private long roleCustom;
    private long roleAll;
    private long mgrSelf;
    private long mgrDept;
    private long mgrDeptBelow;
    private long mgrCustom;
    private long mgrAll;
    private long mgrNone;
    private long userRoot;
    private long userA1;
    private long userA2;
    private long userAChild;
    private long userB1;

    @BeforeEach
    void setUpTenantFixtures() {
        TenantCreateResponse tenant = asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                "ds-" + suffix(), "数据权限用例租户" + suffix(), FULL_PACKAGE_ID, null,
                "admin" + suffix(), "用例管理员", null)));
        tenantId = tenant.tenantId();
        tenantAdminId = tenant.adminUserId();

        asTenant(() -> {
            long rootDeptId = tenant.rootDeptId();
            deptA = deptService.create(new DeptSaveRequest(rootDeptId, "甲部门", 1, 1));
            deptAChild = deptService.create(new DeptSaveRequest(deptA, "甲部门-子部门", 1, 1));
            deptB = deptService.create(new DeptSaveRequest(rootDeptId, "乙部门", 2, 1));

            roleSelf = createRole("self", 1, null);
            roleDept = createRole("dept", 2, null);
            roleDeptBelow = createRole("deptbelow", 3, null);
            roleCustom = createRole("custom", 4, List.of(deptB));
            roleAll = createRole("all", 5, null);
        });

        // 被观察的普通用户:全部由"租户管理员"创建,所以"仅本人"档负责人不该看到他们
        userRoot = createUser("uroot", tenant.rootDeptId(), List.of());
        userA1 = createUser("ua1", deptA, List.of());
        userA2 = createUser("ua2", deptA, List.of());
        userAChild = createUser("uachild", deptAChild, List.of());
        userB1 = createUser("ub1", deptB, List.of());

        // 六个负责人:部门都在甲部门,唯一的差别是角色档位(最后一个没有角色)
        mgrSelf = createUser("mgrself", deptA, List.of(roleSelf));
        mgrDept = createUser("mgrdept", deptA, List.of(roleDept));
        mgrDeptBelow = createUser("mgrbelow", deptA, List.of(roleDeptBelow));
        mgrCustom = createUser("mgrcustom", deptA, List.of(roleCustom));
        mgrAll = createUser("mgrall", deptA, List.of(roleAll));
        mgrNone = createUser("mgrnone", deptA, List.of());
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("用例3:档1(仅本人)——只看到自己,看不到同部门的同事")
    void scopeSelfSeesOnlyItself() {
        assertThat(visibleIdsAs(mgrSelf)).containsExactly(mgrSelf);
    }

    @Test
    @DisplayName("用例3:档2(本部门)——看到本部门全部人,看不到平级部门与根部门")
    void scopeDeptSeesOwnDeptOnly() {
        // 六个负责人都挂在甲部门,所以"本部门"里必然包含他们(这也是档2 与档1 的关键差别)
        assertThat(visibleIdsAs(mgrDept)).containsExactlyInAnyOrder(
                mgrSelf, mgrDept, mgrDeptBelow, mgrCustom, mgrAll, mgrNone,
                userA1, userA2);
    }

    @Test
    @DisplayName("用例3:档3(本部门及以下)——包含直接子部门,仍不含平级部门")
    void scopeDeptAndBelowIncludesSubtree() {
        assertThat(visibleIdsAs(mgrDeptBelow)).containsExactlyInAnyOrder(
                mgrSelf, mgrDept, mgrDeptBelow, mgrCustom, mgrAll, mgrNone,
                userA1, userA2,
                userAChild);
    }

    @Test
    @DisplayName("用例3:档4(自定义部门)——只看到指定的乙部门(自己的账号永远在列表里)")
    void scopeCustomDeptUsesConfiguredDepts() {
        assertThat(visibleIdsAs(mgrCustom)).containsExactlyInAnyOrder(mgrCustom, userB1);
    }

    @Test
    @DisplayName("用例3:档5(全部)——看到整个租户,但依然看不到平台租户的人")
    void scopeAllSeesWholeTenantButNotOtherTenants() {
        assertThat(visibleIdsAs(mgrAll)).containsExactlyInAnyOrder(
                tenantAdminId,
                mgrSelf, mgrDept, mgrDeptBelow, mgrCustom, mgrAll, mgrNone,
                userRoot, userA1, userA2, userAChild, userB1);
    }

    @Test
    @DisplayName("用例4:没有任何角色 → denyAll 兜底,只有自己的账号可读(5.3 的边界)")
    void noRoleFallsBackToDenyAll() {
        assertThat(visibleIdsAs(mgrNone)).containsExactly(mgrNone);
    }

    @Test
    @DisplayName("8.3:档1(仅本人)按 create_by 判定——自己创建的数据可见,同部门的其他人不可见")
    void scopeSelfJudgesByCreateBy() {
        // 以「仅本人」档负责人自己的身份创建一条数据:create_by 就是它自己
        String username = "ownedby" + suffix();
        long ownedById = asUser(mgrSelf, () -> {
            userService.create(new UserSaveRequest(username, null, "负责人自己创建的用户", null, deptA, List.of(), 1));
            return userRepository.findByTenantIdAndUsername(tenantId, username).orElseThrow().getId();
        });

        assertThat(visibleIdsAs(mgrSelf))
                .as("档1 的判据是 create_by,不是 id —— 所以自己建的能看到")
                .contains(ownedById, mgrSelf)
                .doesNotContain(userA1, userA2, userB1);
    }

    @Test
    @DisplayName("8.3:档3(本部门及以下)在四层深链上不遗漏、不越界")
    void scopeDeptAndBelowHandlesDeepHierarchy() {
        long levelThree = asTenant(() -> deptService.create(new DeptSaveRequest(deptAChild, "三级部门", 1, 1)));
        long levelFour = asTenant(() -> deptService.create(new DeptSaveRequest(levelThree, "四级部门", 1, 1)));
        long levelThreeUser = createUser("ul3", levelThree, List.of());
        long levelFourUser = createUser("ul4", levelFour, List.of());

        assertThat(visibleIdsAs(mgrDeptBelow))
                .as("四层深链上的子孙都要在范围内:ancestors 前缀匹配一旦少个逗号,这里就会整片缺失")
                .contains(userAChild, levelThreeUser, levelFourUser)
                .doesNotContain(userRoot, userB1);
    }

    @Test
    @DisplayName("8.3:dept_id 为空的用户,档2/档3 只能看到自己(空部门不能被特判成「看全部」)")
    void userWithoutDeptSeesOnlySelfUnderDeptScopes() {
        long deptScope2 = createRole("nodept2", 2, null);
        long deptScope3 = createRole("nodept3", 3, null);
        long noDeptUserWithScope2 = createUser("nodept2u", null, List.of(deptScope2));
        long noDeptUserWithScope3 = createUser("nodept3u", null, List.of(deptScope3));

        assertThat(visibleIdsAs(noDeptUserWithScope2)).containsExactly(noDeptUserWithScope2);
        assertThat(visibleIdsAs(noDeptUserWithScope3)).containsExactly(noDeptUserWithScope3);
    }

    @Test
    @DisplayName("8.3:档4(自定义部门)看 sys_role_dept 里配置的部门,与自己有没有部门无关")
    void customDeptScopeIgnoresOwnDept() {
        long roleCustomDeptB = createRole("custnodept", 4, List.of(deptB));
        long noDeptUser = createUser("custnodeptu", null, List.of(roleCustomDeptB));

        assertThat(visibleIdsAs(noDeptUser))
                .as("档4 的可见范围来自配置,不是自己的 dept_id")
                .containsExactlyInAnyOrder(noDeptUser, userB1);
    }

    @Test
    @DisplayName("用例5:同租户跨部门越权——档2 负责人读/改/删其他部门的用户一律「资源不存在」,且数据未被改动")
    void crossDeptAccessIsRejectedAsNotFound() {
        String originalNickname = nicknameOf(userB1);

        assertThatThrownBy(() -> asUser(mgrDept, () -> userService.detail(userB1)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).as("不区分「不存在」与「无权限」,避免探测(7.3)")
                                .isEqualTo(ErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> asUser(mgrDept, () -> userService.update(userB1,
                new UserSaveRequest("ub1x", null, "被越权改掉的昵称", null, deptB, List.of(), 1))))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        assertThatThrownBy(() -> asUser(mgrDept, () -> userService.delete(userB1)))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

        assertThat(nicknameOf(userB1)).as("先判权后写入:失败的越权操作不能留下任何改动")
                .isEqualTo(originalNickname);
    }

    @Test
    @DisplayName("用例8:没有审计快照的写入被归属人校验阻断(不能「顺手帮它填一个」)")
    void writeWithoutAuditSnapshotIsBlocked() {
        // 有租户上下文、但**没有** AuditContext:模拟异步/无请求场景忘了透传快照(4.12)
        assertThatThrownBy(() -> TenantContext.callAsTenant(tenantId, false, () -> userService.create(
                new UserSaveRequest("ownerless" + suffix(), null, "无归属人", null, deptA, List.of(), 1))))
                .as("sys_user 标注了 OwnedEntity,create_by 为空时必须失败(5.3 硬约束)")
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("用例17:授权与收权都在下一个请求立即生效——权限缓存不能有「等过期」的窗口(5.5)")
    void permissionChangesTakeEffectImmediately() {
        // 先读一次,把该租户当前版本的空权限写进缓存
        assertThat(permsOf(mgrNone)).doesNotContain(USER_LIST_PERM);

        long roleId = asTenant(() -> roleService.create(
                new RoleCreateRequest("grant" + suffix(), "用户列表角色", 1, null, 1)));
        asTenant(() -> userService.update(mgrNone,
                new UserSaveRequest("mgrnone", null, "改名不影响断言", null, deptA, List.of(roleId), 1)));
        asTenant(() -> roleService.grantMenus(roleId, new RoleMenuGrantRequest(USER_LIST_MENUS)));

        assertThat(permsOf(mgrNone)).as("授权必须立即生效,否则前端拿不到按钮权限(5.5)")
                .contains(USER_LIST_PERM);

        asTenant(() -> roleService.grantMenus(roleId, new RoleMenuGrantRequest(List.of())));

        assertThat(permsOf(mgrNone)).as("收权同样必须立即生效:缓存窗口期就是越权窗口期")
                .doesNotContain(USER_LIST_PERM);
    }

    // ——— 辅助方法 ———

    /** 站在某个用户的视角查用户列表(这才是数据权限真正生效的视角)。 */
    private List<Long> visibleIdsAs(long userId) {
        return asUser(userId, () -> userService.page(null, null, null, 1, 100).list().stream()
                .map(UserView::id)
                .toList());
    }

    private Set<String> permsOf(long userId) {
        // 算权限要读角色的 menuIds(懒加载集合),必须在事务/Session 内。
        // 生产路径上调用方是 @Transactional 的 service 或带 OSIV 的请求;测试里显式补一个事务。
        return asTenant(() -> transactionTemplate.execute(status ->
                permissionProvider.load(userId).permCodes()));
    }

    private String nicknameOf(long userId) {
        return asSuperUser(() -> userRepository.findById(userId).orElseThrow().getNickname());
    }

    private long createRole(String key, int dataScope, List<Long> deptIds) {
        return asTenant(() -> roleService.create(
                new RoleCreateRequest(key + suffix(), "用例角色", dataScope, deptIds, 1)));
    }

    /** {@code deptId} 用包装类型:8.3 要覆盖"没有部门的用户"这种情形。 */
    private long createUser(String namePrefix, Long deptId, List<Long> roleIds) {
        String username = namePrefix + suffix();
        asTenant(() -> userService.create(
                new UserSaveRequest(username, null, "用例用户", null, deptId, roleIds, 1)));
        return asTenant(() -> userRepository.findByTenantIdAndUsername(tenantId, username)
                .orElseThrow().getId());
    }

    /** 每个用例的数据都带独立后缀:用例之间不共享事务,重复执行也不能互相冲突。 */
    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true,
                () -> bindAudit(PLATFORM_TENANT_ID, PLATFORM_ADMIN_USER_ID, action));
    }

    /** 以"该租户管理员"身份执行:它持有档5,setup 与变更操作不会被数据权限挡住。 */
    private <T> T asTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(tenantId, false, () -> bindAudit(tenantId, tenantAdminId, action));
    }

    private void asTenant(Runnable action) {
        asTenant(() -> {
            action.run();
            return null;
        });
    }

    private <T> T asUser(long userId, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantId, false, () -> bindAudit(tenantId, userId, action));
    }

    private void asUser(long userId, Runnable action) {
        asUser(userId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 绑定审计快照。退出时**恢复进入前的值**而不是直接清空:setup 里会出现嵌套(外层 asTenant
     * 里又调 createRole),直接清空会把外层的身份一起抹掉,后面的写入就变成"没有归属人"了。
     */
    private <T> T bindAudit(long boundTenantId, long userId, Supplier<T> action) {
        AuditContext previous = AuditContext.current();
        AuditContext.bind(new AuditContext(boundTenantId, userId, "127.0.0.1", "datascope-it"));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                AuditContext.clear();
            } else {
                AuditContext.bind(previous);
            }
        }
    }
}
