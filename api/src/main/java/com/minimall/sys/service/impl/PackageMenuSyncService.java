package com.minimall.sys.service.impl;

import com.minimall.sys.domain.SysRole;
import com.minimall.sys.domain.SysTenantPackageChange;
import com.minimall.sys.domain.repository.SysRoleRepository;
import com.minimall.sys.domain.repository.SysTenantPackageChangeRepository;
import com.minimall.infra.security.PermissionCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 套餐 → 租户权限的逐租户同步(架构文档 4.8、4.8.1)。
 *
 * <p><b>每个租户一个独立事务({@code REQUIRES_NEW})</b>,这是这段代码最重要的性质:
 * 某个租户同步失败时,其它租户的结果必须已经提交(不能整批回滚),而且失败的那个租户要能被单独重跑。
 * 4.8.1 明确要求"单租户失败不影响其他租户",靠的就是这个传播行为。
 *
 * <p>为什么单独放一个 Bean:同类内部方法调用不走代理,{@code REQUIRES_NEW} 会失效
 * (变成加入外层事务),那就退回成"一个大事务"了 —— 这正是要避免的。
 */
@Service
@Transactional
public class PackageMenuSyncService {

    private static final Logger log = LoggerFactory.getLogger(PackageMenuSyncService.class);

    private final SysRoleRepository roleRepository;
    private final SysTenantPackageChangeRepository changeRepository;
    private final PermissionCacheService permissionCacheService;

    public PackageMenuSyncService(SysRoleRepository roleRepository,
                                  SysTenantPackageChangeRepository changeRepository,
                                  PermissionCacheService permissionCacheService) {
        this.roleRepository = roleRepository;
        this.changeRepository = changeRepository;
        this.permissionCacheService = permissionCacheService;
    }

    /**
     * 按差异同步一个租户:新增只给默认管理员角色,收回对该租户全部角色(4.8)。
     *
     * @param triggerType 1-租户换套餐 2-平台改套餐菜单,写进变更记录用于审计
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncTenant(Long tenantId, Long packageId, Collection<Long> added, Collection<Long> revoked, int triggerType) {
        List<SysRole> roles = roleRepository.findByTenantId(tenantId);

        if (!added.isEmpty()) {
            roles.stream().filter(SysRole::isDefaultRole).findFirst().ifPresent(defaultRole -> {
                defaultRole.getMenuIds().addAll(added);
                roleRepository.save(defaultRole);
            });
        }
        if (!revoked.isEmpty()) {
            // 该租户全部角色(含自定义角色),一条 SQL 覆盖,不做例外(4.8)
            roleRepository.revokeMenusFromTenant(revoked, tenantId);
        }

        SysTenantPackageChange change = new SysTenantPackageChange();
        change.setTenantId(tenantId);
        change.setTriggerType(triggerType);
        change.setOldPackageId(packageId);
        change.setNewPackageId(packageId);
        change.setAddedMenuIds(toJsonArray(added));
        change.setRevokedMenuIds(toJsonArray(revoked));
        changeRepository.save(change);

        permissionCacheService.invalidateTenant(tenantId);
        log.info("套餐菜单已同步到租户 tenantId={} packageId={} 新增={} 收回={}",
                tenantId, packageId, added.size(), revoked.size());
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

    private String toJsonArray(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
