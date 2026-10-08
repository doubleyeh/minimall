package com.minimall.sys.service.impl;

import com.minimall.sys.domain.SysRole;
import com.minimall.sys.domain.SysTenantPackageChange;
import com.minimall.sys.domain.Tenant;
import com.minimall.sys.domain.repository.SysPackageRepository;
import com.minimall.sys.domain.repository.SysRoleRepository;
import com.minimall.sys.domain.repository.TenantRepository;
import com.minimall.sys.service.support.PackageChangeRecorder;
import com.minimall.infra.security.PermissionCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 套餐 → 租户权限的逐租户同步(架构文档 4.8、4.8.1)。
 *
 * <p><b>每个租户一个独立事务({@code REQUIRES_NEW})</b>,这是这段代码最重要的性质:
 * 某个租户同步失败时,其它租户的结果必须已经提交(不能整批回滚),而且失败的那个租户要能被单独重跑。
 * 4.8.1 明确要求"单租户失败不影响其他租户",靠的就是这个传播行为。
 *
 * <p>为什么单独放一个 Bean:同类内部方法调用不走代理,{@code REQUIRES_NEW} 会失效
 * (变成加入外层事务),那就退回成"一个大事务"了 —— 这正是要避免的。
 *
 * <p>{@link #applyPackageChange} 是**默认传播**:它既被"立即换套餐"(要和调用方同一个事务)
 * 调用,也被到点任务(逐租户、本就在事务外)调用,两种情况用默认传播都对。
 */
@Service
@Transactional
public class PackageMenuSyncService {

    private static final Logger log = LoggerFactory.getLogger(PackageMenuSyncService.class);

    private final SysRoleRepository roleRepository;
    private final TenantRepository tenantRepository;
    private final SysPackageRepository packageRepository;
    private final PackageChangeRecorder recorder;
    private final PermissionCacheService permissionCacheService;

    public PackageMenuSyncService(SysRoleRepository roleRepository,
                                  TenantRepository tenantRepository,
                                  SysPackageRepository packageRepository,
                                  PackageChangeRecorder recorder,
                                  PermissionCacheService permissionCacheService) {
        this.roleRepository = roleRepository;
        this.tenantRepository = tenantRepository;
        this.packageRepository = packageRepository;
        this.recorder = recorder;
        this.permissionCacheService = permissionCacheService;
    }

    /**
     * 按差异同步一个租户:新增只给默认管理员角色,收回对该租户全部角色(4.8)。
     *
     * @param triggerType 1-租户换套餐 2-平台改套餐菜单,写进变更记录用于审计
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncTenant(Long tenantId, Long packageId, Collection<Long> added, Collection<Long> revoked, int triggerType) {
        applyMenus(tenantId, added, revoked);
        // 平台改套餐菜单是"立即生效":生效日就是当天
        recorder.record(tenantId, triggerType, packageId, packageId, LocalDate.now(), toSet(added), toSet(revoked));
        permissionCacheService.invalidateTenant(tenantId);
        log.info("套餐菜单已同步到租户 tenantId={} packageId={} 新增={} 收回={}",
                tenantId, packageId, added.size(), revoked.size());
    }

    /**
     * 把租户的套餐切到 {@code targetPackageId}:按当前差量同步角色菜单 → 更新租户当前套餐 → 写记录 → 失效缓存。
     *
     * <p><b>幂等</b>:租户已经是目标套餐时什么都不做(返回 false)。到点任务靠这一条重复跑也安全。
     *
     * <p>同步用的差异是**此刻重算**的(不是预约登记时那份预演差异):登记之后到生效之前若又发生过别的变更,
     * 真正要补/要收的以当时的实际状态为准。
     *
     * @return 是否真的发生了切换
     */
    @Transactional
    public boolean applyPackageChange(Long tenantId, Long targetPackageId, int triggerType) {
        Tenant tenant = tenantRepository.findById(tenantId).orElse(null);
        if (tenant == null) {
            log.warn("套餐变更找不到租户,已跳过 tenantId={}", tenantId);
            return false;
        }
        Long oldPackageId = tenant.getPackageId();
        if (Objects.equals(oldPackageId, targetPackageId)) {
            return false;
        }
        if (!packageRepository.existsById(targetPackageId)) {
            log.warn("套餐变更的目标套餐不存在,已跳过 tenantId={} targetPackageId={}", tenantId, targetPackageId);
            return false;
        }
        Set<Long> oldMenus = recorder.menusOf(oldPackageId);
        Set<Long> newMenus = recorder.menusOf(targetPackageId);
        PackageChangeRecorder.Diff diff = recorder.diff(oldMenus, newMenus);

        applyMenus(tenantId, diff.added(), diff.revoked());
        tenant.setPackageId(targetPackageId);
        recorder.record(tenantId, triggerType, oldPackageId, targetPackageId, LocalDate.now(),
                diff.added(), diff.revoked());
        // 5.5:权限已变,INCR 该租户的权限版本号,新权限在下一个请求生效
        permissionCacheService.invalidateTenant(tenantId);
        log.info("租户套餐已生效 tenantId={} {} -> {} 新增={} 收回={} 触发源={}",
                tenantId, oldPackageId, targetPackageId, diff.added().size(), diff.revoked().size(), triggerType);
        return true;
    }

    /**
     * 把租户的权限**收敛**到套餐范围内(4.8.1 的重跑出口,幂等)。
     *
     * <p>与 {@link #syncTenant} 的区别:差异同步只知道"这次增删了什么",而收敛是"不管中间发生过什么,
     * 让状态回到合法值"。对默认角色直接对齐成套餐菜单(它是套餐的投影,人工改动会被纠正);
     * 对自定义角色只清理套餐外的部分 —— 它在套餐内怎么分配是租户管理员的决定,不该被重新同步改掉。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void convergeTenant(Long tenantId, Long packageId, Set<Long> packageMenus) {
        List<SysRole> roles = roleRepository.findByTenantId(tenantId);
        for (SysRole role : roles) {
            if (role.isDefaultRole()) {
                role.getMenuIds().clear();
                role.getMenuIds().addAll(packageMenus);
            } else {
                role.getMenuIds().removeIf(menuId -> !packageMenus.contains(menuId));
            }
            roleRepository.save(role);
        }
        permissionCacheService.invalidateTenant(tenantId);
        log.info("租户权限已按套餐收敛 tenantId={} packageId={} 角色数={}", tenantId, packageId, roles.size());
    }

    /**
     * 把菜单差异落到该租户的角色上。
     *
     * <p>新增只给默认管理员角色 —— 新增权限是"锦上添花",不主动帮自定义角色加不会有安全风险;
     * 收回必须对**该租户全部角色**(含自定义角色),否则降级没生效:任何绕过默认角色的自定义角色还能访问(4.8)。
     */
    private void applyMenus(Long tenantId, Collection<Long> added, Collection<Long> revoked) {
        if (added != null && !added.isEmpty()) {
            roleRepository.findByTenantId(tenantId).stream()
                    .filter(SysRole::isDefaultRole)
                    .findFirst()
                    .ifPresent(defaultRole -> {
                        defaultRole.getMenuIds().addAll(added);
                        roleRepository.save(defaultRole);
                    });
        }
        if (revoked != null && !revoked.isEmpty()) {
            roleRepository.revokeMenusFromTenant(revoked, tenantId);
        }
    }

    private Set<Long> toSet(Collection<Long> ids) {
        return ids == null ? Set.of() : new java.util.LinkedHashSet<>(ids);
    }
}
