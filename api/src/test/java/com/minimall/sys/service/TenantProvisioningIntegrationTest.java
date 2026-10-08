package com.minimall.sys.service;

import com.minimall.sys.api.dto.PackageChangeView;
import com.minimall.sys.api.dto.PackageMenuSaveRequest;
import com.minimall.sys.api.dto.PackageSaveRequest;
import com.minimall.sys.api.dto.RoleCreateRequest;
import com.minimall.sys.api.dto.RoleMenuGrantRequest;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import com.minimall.sys.api.dto.TenantPackageChangeRequest;
import com.minimall.common.BusinessException;
import com.minimall.sys.domain.SysTenantPackageChange;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.repository.SysRoleRepository;
import com.minimall.sys.domain.repository.SysTenantPackageChangeRepository;
import com.minimall.sys.domain.repository.SysUserRepository;
import com.minimall.sys.domain.repository.TenantRepository;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.security.PermissionCacheService;
import com.minimall.infra.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 建租户与套餐同步的端到端用例(架构文档 4.7、4.8、4.8.1、5.2.1、8.1 用例 12~16)。
 *
 * <p>为什么这些用例必须走**真实的 service**(而不是 mock):它们验证的是"多个机制串起来的结果"——
 * 套餐菜单 → 默认角色 → 授权边界 → 降级收回。任何一个环节的参数拼错(mock 掉就看不见了),
 * 表现都是"降级没生效"这类安全问题。
 *
 * <p>两条实现约束,都不是随意的:
 * <ul>
 *   <li>**不给本类加 {@code @Transactional}**。4.8 的同步是按租户开 {@code REQUIRES_NEW} 独立事务的,
 *       如果外层套一个测试事务,内层事务看不到外层未提交的数据(角色还查不到),断言会假失败。
 *       代价是产生的数据不会回滚——测试库本就是一次性的(见 9.4),且所有名称都带随机后缀不会冲突</li>
 *   <li>所有断言都读**平台超管权限以外的**仓储方法(如 {@link SysRoleRepository#findMenuIdsOfRole}),
 *       因为这些用例断言的是"数据本身对不对",不是"过滤有没有生效"(过滤行为由
 *       {@code TenantIsolationTest} 覆盖)。</li>
 * </ul>
 *
 * <p>用例依赖 V2 种子数据里的菜单 ID(1=系统管理、2=用户管理、11~18=用户管理下的按钮)。
 * 这是种子数据的对外契约,改种子数据时要同步改这里。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class TenantProvisioningIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    private static final long FULL_PACKAGE_ID = 1L;

    /** "系统管理 → 用户管理"这一整棵子树(含父链,因为 5.2.1 要求父链完整)。 */
    private static final List<Long> USER_SUBTREE = List.of(1L, 2L, 11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L);
    /** 只保留"用户列表"按钮的最小集合。 */
    private static final List<Long> MINIMAL_MENUS = List.of(1L, 2L, 11L);
    /** 降级时应当被收回的菜单(在 USER_SUBTREE 里、但不在 MINIMAL_MENUS 里)。 */
    private static final List<Long> MENUS_TO_BE_REVOKED = List.of(12L, 13L, 14L, 15L, 16L, 17L, 18L);

    @Autowired
    private TenantService tenantService;
    @Autowired
    private PackageService packageService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private SysRoleRepository roleRepository;
    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired
    private SysTenantPackageChangeRepository packageChangeRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private PackageChangeService packageChangeService;
    @Autowired
    private PermissionCacheService permissionCacheService;

    @Test
    @DisplayName("用例12:建租户六步——默认角色恰好拥有套餐菜单,管理员用户绑定根部门且强制改密")
    void createTenantProvisionsDefaultRoleDeptAndAdmin() {
        Long packageId = createPackage("受限套餐-" + suffix(), MINIMAL_MENUS);

        TenantCreateResponse created = createTenant(packageId);

        assertThat(roleRepository.findMenuIdsOfRole(created.defaultRoleId()))
                .containsExactlyInAnyOrderElementsOf(MINIMAL_MENUS);

        SysUser admin = userRepository.findById(created.adminUserId()).orElseThrow();
        assertThat(admin.getTenantId()).isEqualTo(created.tenantId());
        assertThat(admin.getDeptId()).isEqualTo(created.rootDeptId());
        assertThat(admin.getIsSuper()).isZero();
        assertThat(admin.getMustChangePassword()).isEqualTo(1);
        // 随机生成的初始密码只回传一次
        assertThat(created.initialPassword()).isNotBlank();
        assertThat(created.mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("用例13:越界授权(套餐外的菜单)必须被拒绝,且不产生任何写入")
    void grantingMenuOutsidePackageIsRejected() {
        Long packageId = createPackage("受限套餐-" + suffix(), MINIMAL_MENUS);
        TenantCreateResponse created = createTenant(packageId);
        Long customRoleId = createRole(created.tenantId(), "custom_" + suffix());

        // 13 = 用户新增,不在该租户的套餐里
        assertThatThrownBy(() -> asTenant(created.tenantId(), () ->
                roleService.grantMenus(customRoleId, new RoleMenuGrantRequest(List.of(1L, 2L, 11L, 13L)))))
                .isInstanceOf(BusinessException.class);

        assertThat(roleRepository.findMenuIdsOfRole(customRoleId)).doesNotContain(13L);
    }

    @Test
    @DisplayName("用例13补充:套餐内的授权正常通过(证明拒绝不是把功能一刀切掉)")
    void grantingMenusInsidePackageSucceeds() {
        Long packageId = createPackage("受限套餐-" + suffix(), MINIMAL_MENUS);
        TenantCreateResponse created = createTenant(packageId);
        Long customRoleId = createRole(created.tenantId(), "custom_" + suffix());

        asTenant(created.tenantId(), () ->
                roleService.grantMenus(customRoleId, new RoleMenuGrantRequest(MINIMAL_MENUS)));

        assertThat(roleRepository.findMenuIdsOfRole(customRoleId))
                .containsExactlyInAnyOrderElementsOf(MINIMAL_MENUS);
    }

    @Test
    @DisplayName("用例14:套餐降级——默认角色与自定义角色都被立即收回(P0 的核心断言)")
    void downgradeRevokesMenusFromAllRoles() {
        Long beforePackageId = createPackage("降级前-" + suffix(), USER_SUBTREE);
        Long afterPackageId = createPackage("降级后-" + suffix(), MINIMAL_MENUS);
        TenantCreateResponse created = createTenant(beforePackageId);

        Long customRoleId = createRole(created.tenantId(), "custom_" + suffix());
        asTenant(created.tenantId(), () ->
                roleService.grantMenus(customRoleId, new RoleMenuGrantRequest(USER_SUBTREE)));
        assertThat(roleRepository.findMenuIdsOfRole(customRoleId)).contains(13L);

        asSuperUser(() -> {
            tenantService.changePackage(created.tenantId(), new TenantPackageChangeRequest(afterPackageId, null));
            return null;
        });

        assertThat(roleRepository.findMenuIdsOfRole(created.defaultRoleId()))
                .containsExactlyInAnyOrderElementsOf(MINIMAL_MENUS);
        // 关键:自定义角色也不能留着套餐外的菜单,否则降级等于没做(4.8)
        assertThat(roleRepository.findMenuIdsOfRole(customRoleId))
                .doesNotContainAnyElementsOf(MENUS_TO_BE_REVOKED)
                .contains(11L);
    }

    @Test
    @DisplayName("用例16:平台直接改套餐内容——绑定该套餐的租户立即同步(4.8.1)")
    void editingPackageMenusPropagatesToBoundTenantsImmediately() {
        Long packageId = createPackage("可改套餐-" + suffix(), USER_SUBTREE);
        TenantCreateResponse created = createTenant(packageId);
        assertThat(roleRepository.findMenuIdsOfRole(created.defaultRoleId())).contains(13L);

        // 平台把套餐收窄
        asSuperUser(() -> {
            packageService.saveMenus(packageId, new PackageMenuSaveRequest(MINIMAL_MENUS));
            return null;
        });

        assertThat(roleRepository.findMenuIdsOfRole(created.defaultRoleId()))
                .containsExactlyInAnyOrderElementsOf(MINIMAL_MENUS);
    }

    @Test
    @DisplayName("全量套餐(种子数据)是升级路径:创建后默认角色拥有全部非平台菜单")
    void fullPackageGrantsAllNonPlatformMenus() {
        TenantCreateResponse created = createTenant(FULL_PACKAGE_ID);

        List<Long> menuIds = roleRepository.findMenuIdsOfRole(created.defaultRoleId());

        assertThat(menuIds).isNotEmpty();
        // 平台专用菜单绝不允许出现在任何租户的授权里(4.10 的第一层防线)
        assertThat(menuIds).doesNotContainAnyElementsOf(List.of(5L, 6L, 7L, 8L));
    }

    @Test
    @DisplayName("用例8.4:运维出口 resync 让「未同步」的租户收敛回套餐权限(4.8.1)")
    void resyncConvergesTenantLeftInUnsyncedState() {
        Long packageId = createPackage("收窄套餐-" + suffix(), MINIMAL_MENUS);
        TenantCreateResponse created = createTenant(packageId);
        Long customRoleId = createRole(created.tenantId(), "custom_" + suffix());

        // 造一个"上次同步失败的残留":套餐外的菜单 13 直接塞进关联表。
        // 用 SQL 而不是接口,是因为正常授权路径会按套餐校验并拒绝(见用例13)——
        // 这个状态本来就只可能由"同步中断"造成,而 resync 这个出口存在的意义正是收拾它。
        jdbcTemplate.update("insert into sys_role_menu (role_id, menu_id) values (?, ?)", customRoleId, 13L);
        assertThat(roleRepository.findMenuIdsOfRole(customRoleId)).contains(13L);

        asSuperUser(() -> {
            packageService.resync(packageId);
            return null;
        });

        assertThat(roleRepository.findMenuIdsOfRole(customRoleId))
                .as("重跑同步必须把套餐外的授权收回来,否则「未同步」状态永远收敛不了")
                .doesNotContain(13L);
    }

    @Test
    @DisplayName("用例19:降级——变更记录里的「收回」与实际从各角色收回的完全一致")
    void downgradeRecordMatchesWhatWasActuallyRevoked() {
        Long widePackageId = createPackage("记录宽-" + suffix(), USER_SUBTREE);
        Long narrowPackageId = createPackage("记录窄-" + suffix(), MINIMAL_MENUS);
        TenantCreateResponse created = createTenant(widePackageId);
        Long customRoleId = createRole(created.tenantId(), "rec_" + suffix());
        asTenant(created.tenantId(), () ->
                roleService.grantMenus(customRoleId, new RoleMenuGrantRequest(USER_SUBTREE)));

        Set<Long> defaultBefore = Set.copyOf(roleRepository.findMenuIdsOfRole(created.defaultRoleId()));
        Set<Long> customBefore = Set.copyOf(roleRepository.findMenuIdsOfRole(customRoleId));

        asSuperUser(() -> {
            tenantService.changePackage(created.tenantId(), new TenantPackageChangeRequest(narrowPackageId, null));
            return null;
        });

        // "实际生效"必须是逐角色算出来的差集,不能直接拿套餐差异当答案 ——
        // 那样只能验证"实现写的常量等于自己",验证不了"角色身上真的少了这些菜单"
        Set<Long> actuallyRevoked = new LinkedHashSet<>(
                onlyIn(defaultBefore, Set.copyOf(roleRepository.findMenuIdsOfRole(created.defaultRoleId()))));
        actuallyRevoked.addAll(
                onlyIn(customBefore, Set.copyOf(roleRepository.findMenuIdsOfRole(customRoleId))));

        SysTenantPackageChange record = changeRecordOf(created.tenantId());

        assertThat(record.getTriggerType()).isEqualTo(SysTenantPackageChange.TRIGGER_TENANT_SWITCH);
        assertThat(record.getOldPackageId()).isEqualTo(widePackageId);
        assertThat(record.getNewPackageId()).isEqualTo(narrowPackageId);
        assertThat(menuIds(record.getRevokedMenuIds()))
                .as("记录说收回了哪些,就必须真的从各角色身上收回来了(否则这条审计流水不可信)")
                .isEqualTo(actuallyRevoked);
        assertThat(actuallyRevoked).containsExactlyInAnyOrderElementsOf(MENUS_TO_BE_REVOKED);
        assertThat(menuIds(record.getAddedMenuIds())).as("降级不该记出新增").isEmpty();
    }

    @Test
    @DisplayName("用例19补充:升级——记录里的「新增」只落到默认管理员角色,自定义角色不受影响")
    void upgradeRecordMatchesActualAssignment() {
        Long narrowPackageId = createPackage("记录窄2-" + suffix(), MINIMAL_MENUS);
        Long widePackageId = createPackage("记录宽2-" + suffix(), USER_SUBTREE);
        TenantCreateResponse created = createTenant(narrowPackageId);
        Long customRoleId = createRole(created.tenantId(), "rec2_" + suffix());
        asTenant(created.tenantId(), () ->
                roleService.grantMenus(customRoleId, new RoleMenuGrantRequest(MINIMAL_MENUS)));

        Set<Long> defaultBefore = Set.copyOf(roleRepository.findMenuIdsOfRole(created.defaultRoleId()));
        Set<Long> customBefore = Set.copyOf(roleRepository.findMenuIdsOfRole(customRoleId));

        asSuperUser(() -> {
            tenantService.changePackage(created.tenantId(), new TenantPackageChangeRequest(widePackageId, null));
            return null;
        });

        // 升级是"变更后多出来的",方向与降级相反
        Set<Long> actuallyAdded = onlyIn(
                Set.copyOf(roleRepository.findMenuIdsOfRole(created.defaultRoleId())), defaultBefore);

        SysTenantPackageChange record = changeRecordOf(created.tenantId());

        assertThat(menuIds(record.getAddedMenuIds()))
                .as("记录里的新增 = 默认角色实际新增的部分")
                .isEqualTo(actuallyAdded);
        assertThat(actuallyAdded)
                .as("升级新增的正是宽套餐比窄套餐多出来的那些菜单")
                .containsExactlyInAnyOrderElementsOf(onlyIn(Set.copyOf(USER_SUBTREE), Set.copyOf(MINIMAL_MENUS)));
        assertThat(roleRepository.findMenuIdsOfRole(customRoleId))
                .as("自定义角色的授权不该被升级动作改动(4.8:新增只同步默认管理员角色)")
                .containsExactlyInAnyOrderElementsOf(customBefore);
    }

    @Test
    @DisplayName("生效日:选当天立即生效(换套餐 + 同步默认角色 + 递增权限版本号)")
    void changePackageWithTodayEffectiveDateAppliesImmediately() {
        Long narrowPackageId = createPackage("即刻窄-" + suffix(), MINIMAL_MENUS);
        Long widePackageId = createPackage("即刻宽-" + suffix(), USER_SUBTREE);
        TenantCreateResponse created = createTenant(narrowPackageId);
        long versionBefore = permissionCacheService.currentVersion(created.tenantId());

        asSuperUser(() -> {
            tenantService.changePackage(created.tenantId(),
                    new TenantPackageChangeRequest(widePackageId, LocalDate.now()));
            return null;
        });

        assertThat(packageIdOf(created.tenantId())).isEqualTo(widePackageId);
        assertThat(roleRepository.findMenuIdsOfRole(created.defaultRoleId()))
                .containsExactlyInAnyOrderElementsOf(USER_SUBTREE);
        assertThat(permissionCacheService.currentVersion(created.tenantId()))
                .as("权限变了就必须让缓存失效,否则最长 2 小时还是旧权限").isGreaterThan(versionBefore);
        SysTenantPackageChange record = changeRecordOf(created.tenantId());
        assertThat(record.getEffectiveDate()).isEqualTo(LocalDate.now());
        assertThat(record.getTriggerType()).isEqualTo(SysTenantPackageChange.TRIGGER_TENANT_SWITCH);
    }

    @Test
    @DisplayName("生效日:选未来日期只登记,package_id / 角色菜单 / 权限缓存版本号都不动")
    void futureEffectiveDateOnlyRegisters() {
        Long narrowPackageId = createPackage("预约窄-" + suffix(), MINIMAL_MENUS);
        Long widePackageId = createPackage("预约宽-" + suffix(), USER_SUBTREE);
        TenantCreateResponse created = createTenant(narrowPackageId);
        Set<Long> menusBefore = Set.copyOf(roleRepository.findMenuIdsOfRole(created.defaultRoleId()));
        long versionBefore = permissionCacheService.currentVersion(created.tenantId());
        LocalDate future = LocalDate.now().plusDays(5);

        asSuperUser(() -> {
            tenantService.changePackage(created.tenantId(), new TenantPackageChangeRequest(widePackageId, future));
            return null;
        });

        assertThat(packageIdOf(created.tenantId())).as("预约不该立即生效").isEqualTo(narrowPackageId);
        assertThat(roleRepository.findMenuIdsOfRole(created.defaultRoleId()))
                .containsExactlyInAnyOrderElementsOf(menusBefore);
        assertThat(permissionCacheService.currentVersion(created.tenantId()))
                .as("没生效就不该动缓存版本号(动了等于白让全租户回源)").isEqualTo(versionBefore);

        SysTenantPackageChange record = changeRecordOf(created.tenantId());
        assertThat(record.getEffectiveDate()).isEqualTo(future);
        assertThat(record.getNewPackageId()).isEqualTo(widePackageId);

        // 任务也不该提前应用:预约的核心语义就是"到点才生效"
        // (这条同时钉住"先按生效日过滤、再取 id 最大"里的过滤那一半)
        packageChangeService.applyDue();
        assertThat(packageIdOf(created.tenantId())).as("没到生效日,任务不能提前切").isEqualTo(narrowPackageId);
    }

    @Test
    @DisplayName("到点任务:把已到生效日的预约应用上,并且重复跑幂等")
    void scheduledTaskAppliesDueChangeIdempotently() {
        Long narrowPackageId = createPackage("到点窄-" + suffix(), MINIMAL_MENUS);
        Long widePackageId = createPackage("到点宽-" + suffix(), USER_SUBTREE);
        TenantCreateResponse created = createTenant(narrowPackageId);
        LocalDate future = LocalDate.now().plusDays(3);
        asSuperUser(() -> {
            tenantService.changePackage(created.tenantId(), new TenantPackageChangeRequest(widePackageId, future));
            return null;
        });
        assertThat(packageIdOf(created.tenantId())).isEqualTo(narrowPackageId);

        // 模拟"时间到了":生效日改成昨天(业务不允许改这一列,只能直接改库)
        moveEffectiveDate(created.tenantId(), future, LocalDate.now().minusDays(1));

        assertThat(packageChangeService.applyDue()).as("该租户应当被应用").isGreaterThanOrEqualTo(1);
        assertThat(packageIdOf(created.tenantId())).isEqualTo(widePackageId);
        assertThat(roleRepository.findMenuIdsOfRole(created.defaultRoleId()))
                .containsExactlyInAnyOrderElementsOf(USER_SUBTREE);
        long versionAfterApply = permissionCacheService.currentVersion(created.tenantId());

        packageChangeService.applyDue();
        assertThat(permissionCacheService.currentVersion(created.tenantId()))
                .as("已经是目标套餐,重复跑不该再同步、也不该再推版本号").isEqualTo(versionAfterApply);
    }

    @Test
    @DisplayName("同一生效日:后写的那条胜出(写错了就再补一条来纠错)")
    void laterRecordWithSameEffectiveDateWins() {
        Long packageA = createPackage("同日A-" + suffix(), MINIMAL_MENUS);
        Long packageB = createPackage("同日B-" + suffix(), USER_SUBTREE);
        Long packageC = createPackage("同日C-" + suffix(), List.of(1L, 2L, 11L, 12L));
        TenantCreateResponse created = createTenant(packageA);
        LocalDate due = LocalDate.now().plusDays(2);

        asSuperUser(() -> {
            tenantService.changePackage(created.tenantId(), new TenantPackageChangeRequest(packageB, due));
            tenantService.changePackage(created.tenantId(), new TenantPackageChangeRequest(packageC, due));
            return null;
        });
        moveEffectiveDate(created.tenantId(), due, LocalDate.now().minusDays(1));
        packageChangeService.applyDue();

        assertThat(packageIdOf(created.tenantId()))
                .as("两条同生效日,id 更大的那条说了算").isEqualTo(packageC);
    }

    @Test
    @DisplayName("立即变更压住更早的预约:到点任务不会把租户切回预约的套餐")
    void immediateChangeSupersedesEarlierReservation() {
        Long packageA = createPackage("压住A-" + suffix(), MINIMAL_MENUS);
        Long reserved = createPackage("压住预约-" + suffix(), USER_SUBTREE);
        Long immediate = createPackage("压住立即-" + suffix(), List.of(1L, 2L, 11L, 12L));
        TenantCreateResponse created = createTenant(packageA);
        LocalDate due = LocalDate.now().plusDays(2);

        asSuperUser(() -> {
            // 先预约,再立即换成另一个套餐:立即那条 id 更大,按规则它才是当前套餐
            tenantService.changePackage(created.tenantId(), new TenantPackageChangeRequest(reserved, due));
            tenantService.changePackage(created.tenantId(),
                    new TenantPackageChangeRequest(immediate, LocalDate.now()));
            return null;
        });
        assertThat(packageIdOf(created.tenantId())).isEqualTo(immediate);

        // 让预约那条也变成"已生效",看任务会不会把租户翻回预约的套餐
        moveEffectiveDate(created.tenantId(), due, LocalDate.now().minusDays(1));
        packageChangeService.applyDue();

        assertThat(packageIdOf(created.tenantId()))
                .as("预约被后写的立即变更取代,到点也不该翻回去").isEqualTo(immediate);
    }

    @Test
    @DisplayName("变更历史:预约那条标未生效,当前生效标记落在已生效里 id 最大的那条")
    void historyMarksEffectiveAndCurrent() {
        Long packageA = createPackage("历史A-" + suffix(), MINIMAL_MENUS);
        Long reserved = createPackage("历史预约-" + suffix(), USER_SUBTREE);
        Long immediate = createPackage("历史立即-" + suffix(), List.of(1L, 2L, 11L, 12L));
        TenantCreateResponse created = createTenant(packageA);
        LocalDate due = LocalDate.now().plusDays(2);

        asSuperUser(() -> {
            tenantService.changePackage(created.tenantId(), new TenantPackageChangeRequest(reserved, due));
            tenantService.changePackage(created.tenantId(),
                    new TenantPackageChangeRequest(immediate, LocalDate.now()));
            return null;
        });

        List<PackageChangeView> history = packageChangeService.history(created.tenantId(), 10);

        assertThat(history).hasSize(2);
        PackageChangeView latest = history.get(0);
        assertThat(latest.newPackageId()).isEqualTo(immediate);
        assertThat(latest.effective()).as("选当天生效 = 已生效").isTrue();
        assertThat(latest.current()).as("已生效里 id 最大的那条才是当前套餐").isTrue();
        assertThat(latest.oldPackageName()).isNotBlank();

        PackageChangeView reservation = history.get(1);
        assertThat(reservation.newPackageId()).isEqualTo(reserved);
        assertThat(reservation.effective()).as("还没到生效日").isFalse();
        assertThat(reservation.current()).isFalse();
        assertThat(reservation.effectiveDate()).isEqualTo(due);
    }

    // ——— 辅助方法 ———

    private SysTenantPackageChange changeRecordOf(Long tenantId) {
        return asSuperUser(() ->
                packageChangeRepository.findFirstByTenantIdOrderByIdDesc(tenantId).orElseThrow());
    }

    /** 租户当前生效套餐(冗余列)。 */
    private Long packageIdOf(Long tenantId) {
        return asSuperUser(() -> tenantRepository.findById(tenantId).orElseThrow().getPackageId());
    }

    /**
     * 模拟"生效日到了":把该租户某个生效日的记录整体挪到另一个日期。
     *
     * <p>业务代码不允许改这一列(它是记录的语义),所以只能直接改库 —— 与商城侧"模拟时间流逝"的用例同一手法。
     */
    private void moveEffectiveDate(Long tenantId, LocalDate from, LocalDate to) {
        jdbcTemplate.update("update sys_tenant_package_change set effective_date = ? "
                        + "where tenant_id = ? and effective_date = ?",
                java.sql.Date.valueOf(to), tenantId, java.sql.Date.valueOf(from));
    }

    /** 记录里的菜单 ID 是 JSON 数组文本;断言只关心其中的数字,抽出来即可(不引入额外界列表依赖)。 */
    private Set<Long> menuIds(String json) {
        Set<Long> ids = new LinkedHashSet<>();
        if (json != null) {
            Matcher matcher = Pattern.compile("\\d+").matcher(json);
            while (matcher.find()) {
                ids.add(Long.parseLong(matcher.group()));
            }
        }
        return ids;
    }

    /**
     * 取"只在 first 里、不在 second 里"的元素。
     *
     * <p>名字刻意不叫 {@code difference}:这个方法的语义有方向,而"变更前/变更后"写反是很容易犯的错
     * (本用例第一版就把升级的差值方向写反了,断言报"记录里是新增、你算出来是空")。
     * 调用处照 {@code onlyIn(变更后, 变更前)} 这种顺序读,就不会歧义。
     */
    private <T> Set<T> onlyIn(Set<T> first, Set<T> second) {
        Set<T> result = new LinkedHashSet<>(first);
        result.removeAll(second);
        return result;
    }

    /** 每个用例用独立后缀:这些用例不共享事务,重复执行也不能互相冲突。 */
    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private Long createPackage(String name, List<Long> menuIds) {
        return asSuperUser(() -> {
            Long packageId = packageService.create(new PackageSaveRequest(name, "集成测试用套餐", 1));
            packageService.saveMenus(packageId, new PackageMenuSaveRequest(menuIds));
            return packageId;
        });
    }

    private TenantCreateResponse createTenant(Long packageId) {
        String suffix = suffix();
        TenantCreateRequest request = new TenantCreateRequest(
                "it-" + suffix,                       // tenantCode
                "集成测试租户" + suffix,                // tenantName
                packageId,
                null,                                  // expireTime:不过期
                "admin" + suffix,                      // adminUsername
                "测试管理员",
                null);                                 // adminPassword:让系统随机生成
        return asSuperUser(() -> tenantService.create(request));
    }

    private Long createRole(Long tenantId, String roleKey) {
        // dataScope = 1(仅本人):用例不关心数据范围,取最窄的档位避免影响其他断言
        return asTenant(tenantId, () ->
                roleService.create(new RoleCreateRequest(roleKey, "自定义角色", 1, null, 1)));
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> bindAuditAndRun(PLATFORM_TENANT_ID, action));
    }

    private <T> T asTenant(Long tenantId, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantId, false, () -> bindAuditAndRun(tenantId, action));
    }

    /** void 版本:写操作(如授权)不返回值,给个重载省得每处写 {@code return null;}。 */
    private void asTenant(Long tenantId, Runnable action) {
        asTenant(tenantId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 绑定审计快照:身份类字段({@code create_by})在同步场景下也来自 {@code AuditContext}(4.4、4.12),
     * 不绑定的话新增数据会因为归属人缺失被 {@code TenantOwnershipListener} 拦下。
     */
    private <T> T bindAuditAndRun(Long tenantId, Supplier<T> action) {
        AuditContext.bind(new AuditContext(tenantId, PLATFORM_ADMIN_USER_ID, "127.0.0.1", "integration-test"));
        try {
            return action.get();
        } finally {
            AuditContext.clear();
        }
    }

    /** 目前仅用于文档化"哪些集合是集合类型需要特殊读法"(set 保留以避免误用 getter)。 */
    @SuppressWarnings("unused")
    private int sizeOf(Set<Long> ids) {
        return ids.size();
    }
}
