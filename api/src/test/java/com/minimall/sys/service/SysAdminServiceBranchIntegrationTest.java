package com.minimall.sys.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.security.PermissionCacheService;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.api.dto.DeptSaveRequest;
import com.minimall.sys.api.dto.DeptTreeNode;
import com.minimall.sys.api.dto.PackageMenuSaveRequest;
import com.minimall.sys.api.dto.PackageSaveRequest;
import com.minimall.sys.api.dto.PackageView;
import com.minimall.sys.api.dto.RoleCreateRequest;
import com.minimall.sys.api.dto.RoleMenuGrantRequest;
import com.minimall.sys.api.dto.RoleView;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import com.minimall.sys.api.dto.UserCreateResponse;
import com.minimall.sys.api.dto.UserResetPasswordResponse;
import com.minimall.sys.api.dto.UserSaveRequest;
import com.minimall.sys.api.dto.UserView;
import com.minimall.sys.domain.SysDept;
import com.minimall.sys.domain.SysPackage;
import com.minimall.sys.domain.SysRole;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.repository.SysDeptRepository;
import com.minimall.sys.domain.repository.SysPackageRepository;
import com.minimall.sys.domain.repository.SysRoleRepository;
import com.minimall.sys.api.dto.TenantView;
import com.minimall.sys.domain.repository.SysUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 平台管理端四个服务的修改 / 删除 / 授权分支(架构文档 5.1、5.2.1、5.3、5.6)。
 *
 * <p>为什么合成一个类:部门、角色、套餐、用户这四个服务的主流程已经被
 * {@code TenantProvisioningIntegrationTest} 与 {@code PermissionMatrixIntegrationTest} 覆盖,
 * 缺的都是"改一处、删一个"这类分支,它们共享同一套前置(一个租户 + 它的默认角色/根部门)。
 * 拆成四个类就要把建租户的流程重复四遍,而建租户是这个项目里最重的一段准备代码。
 *
 * <p>用 {@code @TestInstance(PER_CLASS)} 只建一次租户:每个用例各建一次租户会让整个类慢十倍
 * (建租户要落租户、默认角色、根部门、管理员用户并同步套餐菜单)。代价是用例之间共享状态,
 * 所以下面所有用到名字的地方都带 {@code suffix()},并且只创建、不修改彼此的数据。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SysAdminServiceBranchIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long SEED_ADMIN_ID = 1L;
    private static final long FULL_PACKAGE_ID = 1L;
    /** 平台专用菜单(V3 的字典管理):它不属于任何套餐,用来验"越界授权必须被拒绝"。 */
    private static final long PLATFORM_ONLY_MENU_ID = 9L;
    /** 种子菜单:1=系统管理(目录)、2=用户管理(页面)、11=用户列表(按钮,父级是 2)。 */
    private static final long MENU_SYS = 1L;
    private static final long MENU_USER = 2L;
    private static final long MENU_USER_LIST = 11L;

    @Autowired
    private TenantService tenantService;
    @Autowired
    private DeptService deptService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private PackageService packageService;
    @Autowired
    private UserService userService;
    @Autowired
    private PermissionCacheService permissionCacheService;
    @Autowired
    private SysDeptRepository deptRepository;
    @Autowired
    private SysRoleRepository roleRepository;
    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private SysPackageRepository packageRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long tenantId;
    private long adminUserId;
    private long defaultRoleId;
    private long rootDeptId;

    private final List<Long> createdPackageIds = new ArrayList<>();

    @BeforeAll
    void setUpTenant() {
        TenantCreateResponse tenant = asPlatformAdmin(() -> tenantService.create(new TenantCreateRequest(
                "sysbranch-" + suffix(), "系统分支用例租户" + suffix(), FULL_PACKAGE_ID, null,
                "admin" + suffix(), "用例管理员", null)));
        tenantId = tenant.tenantId();
        adminUserId = tenant.adminUserId();
        defaultRoleId = tenant.defaultRoleId();
        rootDeptId = tenant.rootDeptId();
    }

    @AfterEach
    void cleanCreatedPackages() {
        for (Long packageId : createdPackageIds) {
            // sys_package_menu 是挂在套餐上的 @ElementCollection,删套餐时由 JPA 一并清理
            packageRepository.findById(packageId).ifPresent(packageRepository::delete);
        }
        createdPackageIds.clear();
    }

    // ---------------------------------------------------------------- 上下文

    private <T> T asTenantAdmin(Supplier<T> action) {
        return TenantContext.callAsTenant(tenantId, false, () -> {
            AuditContext.bind(new AuditContext(tenantId, adminUserId, "127.0.0.1", "sys-branch-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private void asTenantAdminRun(Runnable action) {
        asTenantAdmin(() -> {
            action.run();
            return null;
        });
    }

    /**
     * 以指定账号作为"操作人"执行。
     *
     * <p>审计字段的来源是 {@code AuditContext} 里的 userId(经 {@code AuditorAware})，
     * 所以要区分"谁创建的"与"谁改的"，必须能换一个操作人身份跑。
     */
    private <T> T asAuditor(long auditorUserId, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantId, false, () -> {
            AuditContext.bind(new AuditContext(tenantId, auditorUserId, "127.0.0.1", "sys-branch-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private <T> T asPlatformAdmin(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, SEED_ADMIN_ID, "127.0.0.1", "sys-branch-it"));
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

    private int countRows(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ================================================================ 部门

    private Long createDept(Long parentId, String name) {
        return asTenantAdmin(() -> deptService.create(new DeptSaveRequest(parentId, name, 1, 1)));
    }

    @Test
    @DisplayName("部门新增:同名被拒、上级不存在被拒、子部门祖级链自动拼好")
    void deptCreateValidates() {
        String name = "一部" + suffix();
        Long parentId = createDept(0L, name);

        assertThatThrownBy(() -> createDept(0L, name))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("部门名称在同租户内不能重复");
        assertThatThrownBy(() -> createDept(999999L, "挂到不存在的上级" + suffix()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上级部门不存在");

        Long childId = createDept(parentId, "二部" + suffix());
        asTenantAdmin(() -> {
            SysDept parent = deptRepository.findById(parentId).orElseThrow();
            SysDept child = deptRepository.findById(childId).orElseThrow();
            assertThat(parent.getAncestors()).as("根部门的祖级链为空串").isEmpty();
            // 祖级链是"本部门及以下"数据权限的计算基础,只能由服务端维护(5.3)
            assertThat(child.getAncestors()).isEqualTo(String.valueOf(parent.getId()));
            return null;
        });
    }

    @Test
    @DisplayName("部门修改:不能挂到自己或自己的下级(成环会让数据权限算错)")
    void deptUpdateRejectsCycle() {
        Long a = createDept(0L, "环A" + suffix());
        Long b = createDept(a, "环B" + suffix());

        assertThatThrownBy(() -> asTenantAdminRun(() ->
                deptService.update(a, new DeptSaveRequest(a, "环A" + suffix(), 1, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上级部门不能是自己");
        assertThatThrownBy(() -> asTenantAdminRun(() ->
                deptService.update(a, new DeptSaveRequest(b, "环A" + suffix(), 1, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上级部门不能是自己的下级");
    }

    @Test
    @DisplayName("部门修改:移动部门时子孙祖级链级联更新(直接下级也要更新)")
    void deptUpdateCascadesAncestors() {
        Long a = createDept(0L, "迁A" + suffix());
        Long b = createDept(a, "迁B" + suffix());
        Long c = createDept(b, "迁C" + suffix());
        Long newParent = createDept(0L, "新上级" + suffix());

        asTenantAdminRun(() -> deptService.update(a, new DeptSaveRequest(newParent, "迁A" + suffix(), 1, 1)));

        asTenantAdmin(() -> {
            // 祖级链的语义:子部门 = 父部门的祖级链 + 父部门 id(见 DeptServiceImpl#childAncestors)
            assertThat(deptRepository.findById(a).orElseThrow().getAncestors())
                    .isEqualTo(String.valueOf(newParent));
            assertThat(deptRepository.findById(b).orElseThrow().getAncestors())
                    .as("直接下级必须是新上级 + 本部门,漏了它整条链就与真实层级脱节")
                    .isEqualTo(newParent + "," + a);
            assertThat(deptRepository.findById(c).orElseThrow().getAncestors())
                    .as("更深的孙子也要跟着换前缀")
                    .isEqualTo(newParent + "," + a + "," + b);
            return null;
        });
    }

    @Test
    @DisplayName("部门修改:只传名字时不动排序与状态,改名冲突才拒")
    void deptUpdateKeepsUntouchedFields() {
        Long deptId = createDept(0L, "保守改" + suffix());
        // 名字必须先存下来:每次调 suffix() 都会生成新串,写两次就变成两个不同的名字,
        // 那样根本撞不上,断言会以"没抛异常"失败(第一次就是这么写错的)
        String occupiedName = "被占用名" + suffix();
        Long otherId = createDept(0L, occupiedName);

        assertThatThrownBy(() -> asTenantAdminRun(() ->
                deptService.update(deptId, new DeptSaveRequest(0L, occupiedName, 1, 1))))
                .as("改名撞上别人的名字")
                .isInstanceOf(BusinessException.class);

        asTenantAdminRun(() -> deptService.update(deptId, new DeptSaveRequest(0L, "改名后" + suffix(), null, null)));

        asTenantAdmin(() -> {
            SysDept dept = deptRepository.findById(deptId).orElseThrow();
            assertThat(dept.getSortOrder()).as("null 表示不改").isEqualTo(1);
            assertThat(dept.getStatus()).isEqualTo(1);
            assertThat(otherId).isPositive();
            return null;
        });
    }

    @Test
    @DisplayName("部门删除:有下级、有用户都要拒绝;都没有时才能删")
    void deptDeleteValidates() {
        Long parentId = createDept(0L, "待删父" + suffix());
        Long childId = createDept(parentId, "待删子" + suffix());

        assertThatThrownBy(() -> asTenantAdminRun(() -> deptService.delete(parentId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存在下级部门");

        Long withUser = createDept(0L, "有人部门" + suffix());
        UserCreateResponse user = asTenantAdmin(() -> userService.create(new UserSaveRequest(
                "itdept" + suffix(), null, "部门用例用户", null, withUser, List.of(), 1)));
        assertThatThrownBy(() -> asTenantAdminRun(() -> deptService.delete(withUser)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仍有");
        assertThat(user.userId()).isPositive();

        asTenantAdminRun(() -> deptService.delete(childId));
        asTenantAdmin(() -> {
            assertThat(deptRepository.findById(childId)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("部门树:按状态过滤,同级按排序值再按 id 排")
    void deptTreeFiltersAndSorts() {
        Long low = createDept(0L, "排序在后" + suffix());
        Long disabled = asTenantAdmin(() ->
                deptService.create(new DeptSaveRequest(0L, "停用部门" + suffix(), 1, 0)));

        List<DeptTreeNode> enabled = asTenantAdmin(() -> deptService.tree(1));
        assertThat(enabled).extracting(DeptTreeNode::id).doesNotContain(disabled);
        assertThat(enabled).extracting(DeptTreeNode::id).contains(low, rootDeptId);

        List<DeptTreeNode> disabledOnly = asTenantAdmin(() -> deptService.tree(0));
        assertThat(disabledOnly).extracting(DeptTreeNode::id).contains(disabled);
    }

    @Test
    @DisplayName("部门操作:没有租户上下文时直接 UNAUTHORIZED")
    void deptRequiresTenant() {
        assertThatThrownBy(() -> deptService.tree(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    // ================================================================ 角色

    private Long createRole(String key, Integer dataScope, List<Long> deptIds) {
        return asTenantAdmin(() -> roleService.create(
                new RoleCreateRequest(key, "角色" + key, dataScope, deptIds, 1)));
    }

    @Test
    @DisplayName("角色新增:标识重复被拒;自定义部门档必须指定部门且部门要真实存在")
    void roleCreateValidates() {
        String key = "itrole" + suffix();
        createRole(key, 1, null);

        assertThatThrownBy(() -> createRole(key, 1, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色标识已存在");
        assertThatThrownBy(() -> createRole("itrole4" + suffix(), 4, List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须指定部门");
        assertThatThrownBy(() -> createRole("itrole5" + suffix(), 4, List.of(999999L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("部门不存在");
    }

    @Test
    @DisplayName("角色修改:标识不允许改;改 dataScope 要失效该租户权限缓存")
    void roleUpdateValidatesAndInvalidates() {
        String key = "itupd" + suffix();
        Long roleId = createRole(key, 1, null);

        assertThatThrownBy(() -> asTenantAdminRun(() -> roleService.update(roleId,
                new RoleCreateRequest(key + "x", "改标识", 1, null, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色标识不允许修改");

        long before = permissionCacheService.currentVersion(tenantId);
        asTenantAdminRun(() -> roleService.update(roleId, new RoleCreateRequest(key, "改名后", 3, null, 1)));

        asTenantAdmin(() -> {
            SysRole role = roleRepository.findById(roleId).orElseThrow();
            assertThat(role.getRoleName()).isEqualTo("改名后");
            assertThat(role.getDataScope()).isEqualTo(3);
            return null;
        });
        assertThat(permissionCacheService.currentVersion(tenantId))
                .as("数据范围变了,权限缓存必须失效")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("角色停用:失效权限缓存")
    void roleChangeStatusInvalidatesCache() {
        Long roleId = createRole("itstat" + suffix(), 1, null);
        long before = permissionCacheService.currentVersion(tenantId);

        asTenantAdminRun(() -> roleService.changeStatus(roleId, 0));

        assertThat(permissionCacheService.currentVersion(tenantId)).isGreaterThan(before);
        asTenantAdmin(() -> {
            assertThat(roleRepository.findById(roleId).orElseThrow().getStatus()).isZero();
            return null;
        });
    }

    @Test
    @DisplayName("角色删除:默认角色不可删;仍被用户持有不可删;都满足才删得掉")
    void roleDeleteValidates() {
        assertThatThrownBy(() -> asTenantAdminRun(() -> roleService.delete(defaultRoleId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("默认管理员角色不允许删除");

        String key = "itheld" + suffix();
        Long heldRole = createRole(key, 1, null);
        asTenantAdmin(() -> userService.create(new UserSaveRequest(
                "itholder" + suffix(), null, "持有者", null, null, List.of(heldRole), 1)));
        assertThatThrownBy(() -> asTenantAdminRun(() -> roleService.delete(heldRole)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仍被");

        Long freeRole = createRole("itfree" + suffix(), 1, null);
        asTenantAdminRun(() -> roleService.delete(freeRole));
        asTenantAdmin(() -> {
            assertThat(roleRepository.findById(freeRole)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("角色授权:默认角色不可人工改;套餐外菜单必须整单拒绝;正常授权要失效缓存")
    void roleGrantMenusEnforcesPackageScope() {
        assertThatThrownBy(() -> asTenantAdminRun(() ->
                roleService.grantMenus(defaultRoleId, new RoleMenuGrantRequest(List.of(MENU_USER)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("默认管理员角色的菜单由套餐维护");

        Long roleId = createRole("itgrant" + suffix(), 1, null);
        assertThatThrownBy(() -> asTenantAdminRun(() -> roleService.grantMenus(roleId,
                new RoleMenuGrantRequest(List.of(MENU_SYS, MENU_USER, PLATFORM_ONLY_MENU_ID)))))
                .as("不做静默过滤:越界就整单拒绝,否则前端显示的授权与实际生效的不一致")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.MENU_OUT_OF_PACKAGE));

        long before = permissionCacheService.currentVersion(tenantId);
        asTenantAdminRun(() -> roleService.grantMenus(roleId,
                new RoleMenuGrantRequest(List.of(MENU_SYS, MENU_USER, MENU_USER_LIST))));

        assertThat(asTenantAdmin(() -> roleService.grantedMenuIds(roleId)))
                .containsExactlyInAnyOrder(MENU_SYS, MENU_USER, MENU_USER_LIST);
        assertThat(permissionCacheService.currentVersion(tenantId))
                .as("授权变了,该租户下一个请求就该是新权限")
                .isGreaterThan(before);
        assertThat(asTenantAdmin(() -> roleService.grantableMenuTree(roleId)))
                .as("候选菜单树只含套餐内的菜单")
                .isNotEmpty();
    }

    @Test
    @DisplayName("角色列表:按名称模糊与状态过滤")
    void rolePageFilters() {
        String key = "itpage" + suffix();
        Long roleId = createRole(key, 1, null);

        PageResult<RoleView> all = asTenantAdmin(() -> roleService.page(null, null, 1, 50));
        assertThat(all.total()).isGreaterThanOrEqualTo(2);
        PageResult<RoleView> byName = asTenantAdmin(() -> roleService.page("角色" + key, null, 1, 50));
        assertThat(byName.list()).extracting(RoleView::id).contains(roleId);
        PageResult<RoleView> disabledOnly = asTenantAdmin(() -> roleService.page(null, 0, 1, 50));
        assertThat(disabledOnly.list()).extracting(RoleView::id).doesNotContain(roleId);
    }

    // ================================================================ 套餐

    private Long createPackage(String name) {
        Long id = asPlatformAdmin(() -> packageService.create(new PackageSaveRequest(name, "用例套餐", 1)));
        createdPackageIds.add(id);
        return id;
    }

    @Test
    @DisplayName("套餐:重名被拒;改名撞名被拒;禁用不删记录")
    void packageCreateUpdateDisable() {
        String name = "it包" + suffix();
        Long packageId = createPackage(name);

        assertThatThrownBy(() -> createPackage(name))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("套餐名称已存在");

        String otherName = "it包B" + suffix();
        Long otherId = createPackage(otherName);
        assertThatThrownBy(() -> asPlatformAdminRun(() ->
                packageService.update(packageId, new PackageSaveRequest(otherName, null, 1))))
                .isInstanceOf(BusinessException.class);

        asPlatformAdminRun(() -> packageService.update(packageId, new PackageSaveRequest(name, "改备注", null)));
        asPlatformAdminRun(() -> packageService.disable(otherId));

        asPlatformAdmin(() -> {
            SysPackage pkg = packageRepository.findById(packageId).orElseThrow();
            assertThat(pkg.getRemark()).isEqualTo("改备注");
            assertThat(pkg.getStatus()).as("status 传 null 表示不动").isEqualTo(1);
            assertThat(packageRepository.findById(otherId).orElseThrow().getStatus())
                    .as("禁用而不是删除:有租户在用时删掉会让 tenant.package_id 悬空")
                    .isZero();
            return null;
        });
    }

    @Test
    @DisplayName("套餐菜单保存:平台专用菜单、不存在的菜单、父链不完整都要拒绝")
    void packageSaveMenusValidates() {
        Long packageId = createPackage("it校验包" + suffix());

        assertThatThrownBy(() -> asPlatformAdminRun(() -> packageService.saveMenus(packageId,
                new PackageMenuSaveRequest(List.of(PLATFORM_ONLY_MENU_ID)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.MENU_OUT_OF_PACKAGE));

        assertThatThrownBy(() -> asPlatformAdminRun(() -> packageService.saveMenus(packageId,
                new PackageMenuSaveRequest(List.of(MENU_SYS, 999999L)))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存在不存在的菜单ID");

        assertThatThrownBy(() -> asPlatformAdminRun(() -> packageService.saveMenus(packageId,
                new PackageMenuSaveRequest(List.of(MENU_USER_LIST)))))
                .as("勾了子菜单却没勾它的上级,前端渲染出的菜单树会断")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("父链不完整");
    }

    @Test
    @DisplayName("套餐菜单保存:第二次保存同一集合不触发同步;有增删时按差异同步租户")
    void packageSaveMenusSyncsOnlyOnChange() {
        Long packageId = createPackage("it同步包" + suffix());
        List<Long> full = List.of(MENU_SYS, MENU_USER, MENU_USER_LIST);

        asPlatformAdminRun(() -> packageService.saveMenus(packageId, new PackageMenuSaveRequest(full)));
        assertThat(asPlatformAdmin(() -> packageService.menuIds(packageId)))
                .containsExactlyInAnyOrderElementsOf(full);
        assertThat(asPlatformAdmin(() -> packageService.grantableMenuTree(packageId)))
                .as("套餐候选菜单里不该出现平台专用菜单")
                .isNotEmpty();

        // 集合没变:直接返回,不做无意义的租户同步
        int changesBefore = countRows("SELECT COUNT(*) FROM sys_tenant_package_change");
        asPlatformAdminRun(() -> packageService.saveMenus(packageId, new PackageMenuSaveRequest(full)));
        assertThat(countRows("SELECT COUNT(*) FROM sys_tenant_package_change"))
                .as("没有差异就不该产生变更记录")
                .isEqualTo(changesBefore);

        // 有收回:走 syncTenants(该套餐没有租户绑定,所以是空跑但覆盖了差异计算与同步入口)
        asPlatformAdminRun(() -> packageService.saveMenus(packageId,
                new PackageMenuSaveRequest(List.of(MENU_SYS, MENU_USER))));
        assertThat(asPlatformAdmin(() -> packageService.menuIds(packageId))).hasSize(2);

        // 清空也是合法请求
        asPlatformAdminRun(() -> packageService.saveMenus(packageId, new PackageMenuSaveRequest(List.of())));
        assertThat(asPlatformAdmin(() -> packageService.menuIds(packageId))).isEmpty();

        // resync 是 void:用 runnable 形式包一层(顺带覆盖"逐租户收敛"的入口)
        asPlatformAdminRun(() -> packageService.resync(packageId));
    }

    @Test
    @DisplayName("套餐列表:按名称与状态过滤")
    void packagePageFilters() {
        String name = "it列表包" + suffix();
        Long packageId = createPackage(name);

        PageResult<PackageView> byName = asPlatformAdmin(() -> packageService.page(name, null, 1, 50));
        assertThat(byName.list()).extracting(PackageView::id).contains(packageId);
        assertThat(byName.list().get(0).menuCount()).as("新套餐没有菜单").isZero();
    }

    // ================================================================ 用户

    @Test
    @DisplayName("用户新增:随机密码时返回明文并要求首登改密;指定密码时两者都不出现")
    void userCreatePasswordHandling() {
        UserCreateResponse generated = asTenantAdmin(() -> userService.create(new UserSaveRequest(
                "itgen" + suffix(), null, "随机密码", null, null, List.of(), 1)));
        assertThat(generated.initialPassword()).as("明文只返回一次").isNotBlank();
        assertThat(generated.mustChangePassword()).isTrue();

        UserCreateResponse fixed = asTenantAdmin(() -> userService.create(new UserSaveRequest(
                "itfix" + suffix(), "Fixed@12345", "指定密码", null, null, List.of(), 1)));
        assertThat(fixed.initialPassword()).as("管理员自己定的密码不该回显").isNull();
        assertThat(fixed.mustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("用户新增:用户名重复、部门不存在、角色不属于本租户都要拒绝")
    void userCreateValidates() {
        String username = "itdup" + suffix();
        asTenantAdmin(() -> userService.create(new UserSaveRequest(username, null, "重复名", null, null, List.of(), 1)));

        assertThatThrownBy(() -> asTenantAdmin(() -> userService.create(new UserSaveRequest(
                username, null, "重复名", null, null, List.of(), 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名已存在");
        assertThatThrownBy(() -> asTenantAdmin(() -> userService.create(new UserSaveRequest(
                "itdeptbad" + suffix(), null, "部门不存在", null, 999999L, List.of(), 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("部门不存在");

        // 平台租户的角色:不属于当前租户(角色查询带租户条件)
        Long foreignRole = asPlatformAdmin(() -> roleService.create(
                new RoleCreateRequest("itforeign" + suffix(), "别租户角色", 1, null, 1)));
        assertThatThrownBy(() -> asTenantAdmin(() -> userService.create(new UserSaveRequest(
                "itrolebad" + suffix(), null, "角色越界", null, null, List.of(foreignRole), 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前租户");
    }

    @Test
    @DisplayName("用户修改:角色变化才失效权限缓存;手机号在视图里脱敏")
    void userUpdateInvalidatesOnlyOnRoleChange() {
        Long userId = asTenantAdmin(() -> userService.create(new UserSaveRequest(
                "itupd" + suffix(), null, "改用户", "13800001111", null, List.of(), 1))).userId();

        long before = permissionCacheService.currentVersion(tenantId);
        asTenantAdminRun(() -> userService.update(userId, new UserSaveRequest(
                "ignored", null, "改了昵称", "13800001111", null, List.of(), 1)));
        assertThat(permissionCacheService.currentVersion(tenantId))
                .as("只改昵称不动角色,不该失效权限缓存")
                .isEqualTo(before);

        String roleKey = "itnewrole" + suffix();
        Long roleId = asTenantAdmin(() -> roleService.create(
                new RoleCreateRequest(roleKey, "新角色", 1, null, 1)));
        asTenantAdminRun(() -> userService.update(userId, new UserSaveRequest(
                "ignored", null, "改了昵称", "13800001111", null, List.of(roleId), 1)));

        assertThat(permissionCacheService.currentVersion(tenantId))
                .as("角色变了 → 权限集合变了 → 必须失效")
                .isGreaterThan(before);

        UserView view = asTenantAdmin(() -> userService.detail(userId));
        assertThat(view.phone()).as("服务端脱敏,不把明文交给前端").isEqualTo("138****1111");
        assertThat(view.nickname()).isEqualTo("改了昵称");
    }

    @Test
    @DisplayName("用户删除:最后一个管理员不可删;普通用户可删")
    void userDeleteNeedsRequestContext() {
        Long userId = asTenantAdmin(() -> userService.create(new UserSaveRequest(
                "itdel" + suffix(), null, "待删用户", null, null, List.of(), 1))).userId();

        // delete 的第一步会取"当前登录用户"(防止把自己删掉),走的是 StpUtil.getLoginIdDefaultNull()。
        // 那是请求作用域的静态入口,不是 HTTP 请求时会抛 SaTokenContextException ——
        // 也就是说这条路径**只能靠 HTTP 用例覆盖**,服务层集成测试碰不到它后面的业务分支
        // (最后管理员保护、刷新令牌撤销都在那之后)。
        // 这里把约束本身钉住:以后有人把 currentUserId 改成可注入的依赖,这条用例会立刻失败并提醒。
        assertThatThrownBy(() -> asTenantAdminRun(() -> userService.delete(userId)))
                .isInstanceOf(cn.dev33.satoken.exception.SaTokenContextException.class);
        assertThat(adminUserId).isPositive();

        // 绕过服务直接清掉,避免给后续用例留下垃圾数据
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        assertThat(userRepository.findById(userId)).isEmpty();
    }

    @Test
    @DisplayName("用户停用与重置密码:都要撤回刷新令牌并清掉锁定状态")
    void userStatusResetAndUnlock() {
        Long userId = asTenantAdmin(() -> userService.create(new UserSaveRequest(
                "itpwd" + suffix(), null, "密码用例用户", null, null, List.of(), 1))).userId();

        // 先制造一个"被锁定"的状态,验证重置密码会把它清掉
        asTenantAdmin(() -> {
            SysUser user = userRepository.findById(userId).orElseThrow();
            user.setLoginFailCount(5);
            user.setLockTime(java.time.LocalDateTime.now());
            userRepository.save(user);
            return null;
        });

        UserResetPasswordResponse reset = asTenantAdmin(() -> userService.resetPassword(userId));
        assertThat(reset.initialPassword()).isNotBlank();
        assertThat(reset.mustChangePassword()).isTrue();
        asTenantAdmin(() -> {
            SysUser user = userRepository.findById(userId).orElseThrow();
            assertThat(user.getLockTime()).as("重置密码要顺手解锁").isNull();
            assertThat(user.getLoginFailCount()).isZero();
            assertThat(user.isMustChangePassword()).isTrue();
            return null;
        });

        asTenantAdminRun(() -> userService.changeStatus(userId, 0));
        asTenantAdmin(() -> {
            assertThat(userRepository.findById(userId).orElseThrow().getStatus()).isZero();
            return null;
        });

        asTenantAdmin(() -> {
            SysUser user = userRepository.findById(userId).orElseThrow();
            user.setLoginFailCount(3);
            user.setLockTime(java.time.LocalDateTime.now());
            userRepository.save(user);
            return null;
        });
        asTenantAdminRun(() -> userService.unlock(userId));
        asTenantAdmin(() -> {
            SysUser user = userRepository.findById(userId).orElseThrow();
            assertThat(user.getLoginFailCount()).isZero();
            assertThat(user.getLockTime()).isNull();
            return null;
        });
    }

    @Test
    @DisplayName("用户列表:按用户名、部门、状态过滤,并带上部门名")
    void userPageFilters() {
        String username = "itlist" + suffix();
        Long deptId = createDept(0L, "列表部门" + suffix());
        asTenantAdmin(() -> userService.create(new UserSaveRequest(
                username, null, "列表用户", null, deptId, List.of(), 1)));

        PageResult<UserView> byName = asTenantAdmin(() -> userService.page(username, null, null, 1, 50));
        assertThat(byName.list()).hasSize(1);
        assertThat(byName.list().get(0).deptName()).as("列表要带部门名,前端不然只显示一个 id").isNotBlank();

        PageResult<UserView> byDept = asTenantAdmin(() -> userService.page(null, deptId, null, 1, 50));
        assertThat(byDept.list()).extracting(UserView::username).contains(username);

        PageResult<UserView> disabledOnly = asTenantAdmin(() -> userService.page(null, null, 0, 1, 50));
        assertThat(disabledOnly.list()).extracting(UserView::username).doesNotContain(username);
    }

    @Test
    @DisplayName("不存在的用户:详情/修改/删除/停用/解锁/重置密码统一按资源不存在(7.3 越权防护)")
    void userOperationsOnUnknownIdAreNotFound() {
        long unknownId = 999999L;

        assertThatThrownBy(() -> asTenantAdmin(() -> userService.detail(unknownId)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> asTenantAdminRun(() -> userService.update(unknownId,
                new UserSaveRequest("whoever", null, "不存在", null, null, List.of(), 1))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> asTenantAdminRun(() -> userService.delete(unknownId)))
                .as("按 ID 的写操作都要先经过滤查询加载实体,加载不到就是统一的资源不存在;"
                        + "用 bulk 语句直接改会绕过数据权限")
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> asTenantAdminRun(() -> userService.changeStatus(unknownId, 0)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> asTenantAdminRun(() -> userService.unlock(unknownId)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> asTenantAdmin(() -> userService.resetPassword(unknownId)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("用户详情:没有部门的用户不报错,部门名为空(不能因为查不到部门名就失败)")
    void userDetailWithoutDept() {
        String username = "itnodept" + suffix();
        Long userId = asTenantAdmin(() -> userService.create(new UserSaveRequest(
                username, null, "无部门用户", null, null, List.of(), null)).userId());

        UserView view = asTenantAdmin(() -> userService.detail(userId));
        assertThat(view.username()).isEqualTo(username);
        assertThat(view.deptId()).isNull();
        assertThat(view.deptName()).isNull();
        assertThat(view.status()).as("不传状态时默认启用").isEqualTo(1);
        // 用 SQL 数而不是读实体的 roleIds:它是懒加载的 @ElementCollection,
        // 而这里没有事务(仓储方法自己的事务已经结束),直接访问会抛 LazyInitializationException
        assertThat(countRows("SELECT COUNT(*) FROM sys_user_role WHERE user_id = ?", userId))
                .as("不挂角色也要能建出来(先建人再授权是常见顺序)")
                .isZero();
    }

    @Test
    @DisplayName("用户修改:字段传 null 表示不改,不能把已有的角色或状态清掉")
    void userUpdateWithNullFieldsKeepsExistingValues() {
        String username = "itnullupd" + suffix();
        Long roleId = createRole("nullupd" + suffix(), 1, null);
        Long userId = asTenantAdmin(() -> userService.create(new UserSaveRequest(
                username, null, "原昵称", "13800000000", null, List.of(roleId), 0)).userId());

        // 只改昵称:roleIds 与 status 都传 null
        asTenantAdminRun(() -> userService.update(userId, new UserSaveRequest(
                username, null, "新昵称", "13800000000", null, null, null)));

        UserView view = asTenantAdmin(() -> userService.detail(userId));
        assertThat(view.nickname()).isEqualTo("新昵称");
        assertThat(countRows("SELECT COUNT(*) FROM sys_user_role WHERE user_id = ? AND role_id = ?", userId, roleId))
                .as("传 null 表示不改角色;若当成空集合处理,用户的权限会被静默清空")
                .isEqualTo(1);
        assertThat(view.status())
                .as("传 null 表示不改状态;若当成 0 处理,用户会被静默停用")
                .isZero();
    }

    @Test
    @DisplayName("用户列表:页码与页大小越界时被钳制,不抛异常(端上偶发传 0 不该 500)")
    void userPageClampsPageArguments() {
        PageResult<UserView> zeroPage = asTenantAdmin(() -> userService.page(null, null, null, 0, 0));
        assertThat(zeroPage.total()).as("pageNo/pageSize 为 0 时按最小合法值处理").isPositive();

        PageResult<UserView> hugePage = asTenantAdmin(() -> userService.page(null, null, null, 9999, 5));
        assertThat(hugePage.list()).isEmpty();
    }

    @Test
    @DisplayName("租户:编码重复被拒;列表按编码与状态过滤,查不到时也要正常返回空页")
    void tenantCreateAndPageFilters() {
        String tenantCode = "syspage-" + suffix();
        asPlatformAdmin(() -> tenantService.create(new TenantCreateRequest(
                tenantCode, "过滤用例租户", FULL_PACKAGE_ID, null, "admin" + suffix(), "用例管理员", null)));

        assertThatThrownBy(() -> asPlatformAdmin(() -> tenantService.create(new TenantCreateRequest(
                tenantCode, "重复编码", FULL_PACKAGE_ID, null, "admin" + suffix(), "用例管理员", null))))
                .as("租户编码是登录时定位租户的唯一依据,重复了整条登录链路都不可用")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DATA_CONFLICT));

        PageResult<TenantView> byCode = asPlatformAdmin(() -> tenantService.page(tenantCode, null, 1, 50));
        assertThat(byCode.list()).extracting(TenantView::tenantCode).containsExactly(tenantCode);
        assertThat(byCode.list().get(0).packageName())
                .as("列表要带套餐名,前端不然只显示一个 id")
                .isNotBlank();

        assertThat(asPlatformAdmin(() -> tenantService.page(tenantCode, 0, 1, 50)).list())
                .as("刚建的租户是启用状态")
                .isEmpty();

        // 查不到任何租户时,页内没有任何 packageId —— 拼套餐名那一步必须走"空集合直接返回 Map.of()",
        // 否则 findAllById(空集合) 在某些实现下会退化成全表查询
        PageResult<TenantView> empty = asPlatformAdmin(() -> tenantService.page("no-such-code-" + suffix(), null, 1, 50));
        assertThat(empty.total()).isZero();
        assertThat(empty.list()).isEmpty();
    }

    @Test
    @DisplayName("租户换套餐:换成同一个套餐时直接返回,不重复做一次全量同步")
    void changePackageToSamePackageIsNoop() {
        // 本类共用租户的套餐就是全量套餐;再"换"成它是空操作 ——
        // 不挡这一下的话,每次保存租户表单都会对全部角色做一次全量差异同步
        asPlatformAdmin(() -> {
            tenantService.changePackage(tenantId, new com.minimall.sys.api.dto.TenantPackageChangeRequest(FULL_PACKAGE_ID));
            return null;
        });
        assertThat(asPlatformAdmin(() -> tenantService.page(null, null, 1, 100)).list())
                .as("租户应当照常可用").isNotEmpty();
    }

    @Test
    @DisplayName("用户新增:没有租户上下文时直接 UNAUTHORIZED(不去猜一个租户)")
    void userRequiresTenant() {
        // 注意用 create 而不是 page:page 只做查询,没有租户上下文时租户过滤器会绑哨兵值返回空集,
        // 它不会抛 UNAUTHORIZED —— 抛异常的是那些"要往某个租户写数据"的入口
        assertThatThrownBy(() -> userService.create(new UserSaveRequest(
                "itnotenant" + suffix(), null, "无租户", null, null, List.of(), 1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    @DisplayName("审计字段:新建填创建人,改动刷新修改人与时间,创建人始终不被改写(4.4)")
    void auditFieldsFollowTheActor() {
        // 用两个**真实账号**分别扮演创建人与修改人:create_by/update_by 若带外键,
        // 用编造的 id 会直接插不进去(那样这条用例会以插库失败告终,而不是断言失败)。
        //
        // 修改人必须**带角色**:数据权限按当前操作人的角色算,没有角色的账号算不出可见范围,
        // 会被按 denyAll 处理 —— 此时连"按 id 读一个本租户用户"都会被过滤成空,报资源不存在。
        // (第一次写这条用例就踩到了:修改人没有角色,update 直接 404。)
        String username = "itaudit" + suffix();
        Long modifierId = asTenantAdmin(() -> userService.create(new UserSaveRequest(
                "itauditmod" + suffix(), null, "修改人", null, null, List.of(defaultRoleId), 1)).userId());

        Long userId = asAuditor(adminUserId, () -> userService.create(new UserSaveRequest(
                username, null, "被审计用户", null, null, List.of(), 1))).userId();

        SysUser created = asTenantAdmin(() -> userRepository.findById(userId).orElseThrow());
        assertThat(created.getCreateBy()).as("创建人取自 AuditContext,不是 Sa-Token 会话").isEqualTo(adminUserId);
        assertThat(created.getCreateTime()).isNotNull();
        assertThat(created.getUpdateTime())
                .as("update_time 是 not null 列,插入时就要有值")
                .isNotNull();
        assertThat(created.getUpdateBy()).as("插入时 Spring Data 也会填一次最后修改人").isEqualTo(adminUserId);

        // 把 update_time 手工改到昨天。再改一次用户,它必须被刷新到现在 ——
        // 否则审计时间线会停在过去,而且这件事不会报任何错
        asTenantAdminRun(() -> jdbcTemplate.update("UPDATE sys_user SET update_time = ? WHERE id = ?",
                LocalDateTime.now().minusDays(1), userId));

        asAuditor(modifierId, () -> {
            userService.update(userId, new UserSaveRequest(username, null, "被改过的昵称", null, null, null, null));
            return null;
        });

        SysUser updated = asTenantAdmin(() -> userRepository.findById(userId).orElseThrow());
        assertThat(updated.getNickname()).isEqualTo("被改过的昵称");
        assertThat(updated.getUpdateBy()).as("修改人要跟着操作人变").isEqualTo(modifierId);
        assertThat(updated.getUpdateTime())
                .as("修改时间必须被刷新,否则审计轨迹显示不出来什么时候改的")
                .isAfter(LocalDateTime.now().minusHours(1));
        assertThat(updated.getCreateBy())
                .as("create_by 是 updatable = false,任何改动都不能改写创建人")
                .isEqualTo(adminUserId);
    }

    private void asPlatformAdminRun(Runnable action) {
        asPlatformAdmin(() -> {
            action.run();
            return null;
        });
    }
}
