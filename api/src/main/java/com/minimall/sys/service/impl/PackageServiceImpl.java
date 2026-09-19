package com.minimall.sys.service.impl;

import com.minimall.sys.api.dto.MenuTreeNode;
import com.minimall.sys.api.dto.PackageMenuSaveRequest;
import com.minimall.sys.api.dto.PackageSaveRequest;
import com.minimall.sys.api.dto.PackageView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.sys.domain.QSysPackage;
import com.minimall.sys.domain.SysMenu;
import com.minimall.sys.domain.SysPackage;
import com.minimall.sys.domain.SysTenantPackageChange;
import com.minimall.sys.domain.Tenant;
import com.minimall.sys.domain.repository.SysMenuRepository;
import com.minimall.sys.domain.repository.SysPackageRepository;
import com.minimall.sys.domain.repository.TenantRepository;
import com.minimall.sys.service.PackageService;
import com.minimall.sys.service.support.MenuTreeBuilder;
import com.querydsl.core.BooleanBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 套餐管理实现(架构文档 4.7、4.8.1、5.2.1、5.6)。
 *
 * <p>本类只做"编排":真正落到某张租户身上的写操作在 {@link PackageMenuSyncService} 里,
 * 由它以 {@code REQUIRES_NEW} 逐租户提交。这样拆分的目的是保证
 * "单租户失败不影响其他租户"(4.8.1),而不是把 20 个租户塞进一个大事务。
 */
@Service
@Transactional
public class PackageServiceImpl implements PackageService {

    private static final Logger log = LoggerFactory.getLogger(PackageServiceImpl.class);

    private final SysPackageRepository packageRepository;
    private final SysMenuRepository menuRepository;
    private final TenantRepository tenantRepository;
    private final PackageMenuSyncService syncService;

    public PackageServiceImpl(SysPackageRepository packageRepository,
                              SysMenuRepository menuRepository,
                              TenantRepository tenantRepository,
                              PackageMenuSyncService syncService) {
        this.packageRepository = packageRepository;
        this.menuRepository = menuRepository;
        this.tenantRepository = tenantRepository;
        this.syncService = syncService;
    }

    @Override
    public PageResult<PackageView> page(String packageName, Integer status, int pageNo, int pageSize) {
        QSysPackage qPackage = QSysPackage.sysPackage;
        BooleanBuilder where = new BooleanBuilder();
        if (packageName != null && !packageName.isBlank()) {
            where.and(qPackage.packageName.contains(packageName));
        }
        if (status != null) {
            where.and(qPackage.status.eq(status));
        }
        Page<SysPackage> page = packageRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1)));

        List<PackageView> views = page.getContent().stream()
                .map(pkg -> new PackageView(pkg.getId(), pkg.getPackageName(), pkg.getRemark(), pkg.getStatus(),
                        (long) pkg.getMenuIds().size(),
                        tenantRepository.countByPackageId(pkg.getId()),
                        pkg.getCreateTime()))
                .toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public Long create(PackageSaveRequest request) {
        if (packageRepository.existsByPackageName(request.packageName())) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "套餐名称已存在");
        }
        SysPackage pkg = new SysPackage();
        pkg.setPackageName(request.packageName());
        pkg.setRemark(request.remark());
        pkg.setStatus(request.status() == null ? 1 : request.status());
        return packageRepository.save(pkg).getId();
    }

    @Override
    public void update(Long packageId, PackageSaveRequest request) {
        SysPackage pkg = load(packageId);
        if (!pkg.getPackageName().equals(request.packageName())
                && packageRepository.existsByPackageName(request.packageName())) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "套餐名称已存在");
        }
        pkg.setPackageName(request.packageName());
        pkg.setRemark(request.remark());
        if (request.status() != null) {
            pkg.setStatus(request.status());
        }
    }

    @Override
    public void disable(Long packageId) {
        // 不做物理删除(5.6):有租户在用时删掉会让 tenant.package_id 指向不存在的记录
        load(packageId).setStatus(0);
    }

    @Override
    public List<MenuTreeNode> grantableMenuTree(Long packageId) {
        load(packageId);
        // 套餐的候选菜单 = 全部非平台专用菜单(5.2.1)。平台专用菜单永远不进套餐,这是 4.10 的第一层防线
        return MenuTreeBuilder.build(menuRepository.findAllNonPlatform());
    }

    @Override
    public List<Long> menuIds(Long packageId) {
        return List.copyOf(load(packageId).getMenuIds());
    }

    /**
     * 4.8.1:保存套餐菜单并**立即**对绑定该套餐的全部租户生效。
     */
    @Override
    public void saveMenus(Long packageId, PackageMenuSaveRequest request) {
        SysPackage pkg = load(packageId);
        // 修改前先把旧的菜单集合读出来 —— 改完就算不出差异了(4.8.1 步骤 1)
        Set<Long> oldMenus = new LinkedHashSet<>(pkg.getMenuIds());
        Set<Long> newMenus = validateMenus(request.menuIds());

        Set<Long> added = difference(newMenus, oldMenus);
        Set<Long> revoked = difference(oldMenus, newMenus);

        pkg.getMenuIds().clear();
        pkg.getMenuIds().addAll(newMenus);
        packageRepository.save(pkg);

        if (added.isEmpty() && revoked.isEmpty()) {
            return;
        }
        syncTenants(packageId, added, revoked, SysTenantPackageChange.TRIGGER_PACKAGE_MENU_EDIT);
    }

    @Override
    public void resync(Long packageId) {
        SysPackage pkg = load(packageId);
        Set<Long> packageMenus = new LinkedHashSet<>(pkg.getMenuIds());
        List<Tenant> tenants = tenantRepository.findByPackageId(packageId);
        int failed = 0;
        for (Tenant tenant : tenants) {
            try {
                syncService.convergeTenant(tenant.getId(), packageId, packageMenus);
            } catch (Exception ex) {
                failed++;
                log.error("租户权限收敛失败,需再次重跑 tenantId={} packageId={}", tenant.getId(), packageId, ex);
            }
        }
        log.info("套餐权限重新同步完成 packageId={} 租户数={} 失败={}", packageId, tenants.size(), failed);
    }

    /**
     * 逐租户同步。**单个租户失败只记录、不中断、不回滚已成功的租户**(4.8.1 步骤 5)。
     */
    private void syncTenants(Long packageId, Set<Long> added, Set<Long> revoked, int triggerType) {
        List<Tenant> tenants = tenantRepository.findByPackageId(packageId);
        int failed = 0;
        for (Tenant tenant : tenants) {
            try {
                syncService.syncTenant(tenant.getId(), packageId, added, revoked, triggerType);
            } catch (Exception ex) {
                failed++;
                log.error("套餐同步失败租户 tenantId={} packageId={} —— 该租户停留在未同步状态,可用 resync 重跑",
                        tenant.getId(), packageId, ex);
            }
        }
        if (failed > 0) {
            log.warn("套餐同步部分失败 packageId={} 总租户={} 失败={}", packageId, tenants.size(), failed);
        }
    }

    /**
     * 套餐菜单的合法性校验(5.2.1 规则 4):
     * ①菜单必须存在;②不含平台专用菜单;③菜单树的父链必须完整(否则前端渲染出的菜单树会断)。
     */
    private Set<Long> validateMenus(Collection<Long> menuIds) {
        Set<Long> requested = new LinkedHashSet<>(menuIds);
        if (requested.isEmpty()) {
            return requested;
        }
        Map<Long, SysMenu> menus = menuRepository.findByIdIn(requested).stream()
                .collect(Collectors.toMap(SysMenu::getId, Function.identity(), (a, b) -> a));
        if (menus.size() != requested.size()) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "存在不存在的菜单ID");
        }
        for (SysMenu menu : menus.values()) {
            if (menu.isPlatformOnly()) {
                throw new BusinessException(ErrorCode.MENU_OUT_OF_PACKAGE,
                        "平台专用菜单不允许进入套餐:" + menu.getMenuName());
            }
        }
        for (SysMenu menu : menus.values()) {
            Long parentId = menu.getParentId();
            while (parentId != null && parentId != 0L) {
                if (!menus.containsKey(parentId)) {
                    throw new BusinessException(ErrorCode.BUSINESS_ERROR,
                            "菜单树父链不完整,缺少上级菜单ID:" + parentId + "(来自菜单 " + menu.getMenuName() + ")");
                }
                parentId = menus.get(parentId).getParentId();
            }
        }
        return requested;
    }

    private Set<Long> difference(Set<Long> source, Set<Long> other) {
        Set<Long> result = new LinkedHashSet<>(source);
        result.removeAll(other);
        return result;
    }

    private SysPackage load(Long packageId) {
        return packageRepository.findById(packageId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
}
