package com.minimall.sys.service.impl;

import com.minimall.infra.security.PermissionCacheService;
import com.minimall.sys.domain.Tenant;
import com.minimall.sys.domain.repository.SysPackageRepository;
import com.minimall.sys.domain.repository.TenantRepository;
import com.minimall.sys.service.support.PackageChangeRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * 租户套餐的切换(架构文档 4.8)。类名刻意不叫 "sync":这里不向角色写任何东西。
 *
 * <p><b>只做一件事:把 {@code tenant.package_id} 改成目标套餐,并写一条变更记录。</b>
 * 角色菜单不动 —— 套餐在权限计算时是上限(角色 ∩ 套餐 ∩ 启用),所以不需要把套餐菜单
 * 物化进 {@code sys_role_menu},平台改一次套餐也不再扇出到 N 个租户。
 *
 * <p>{@code @Transactional} 用默认传播:它既被"立即换套餐"(要和调用方同一个事务)调用,
 * 也被到点任务(逐租户、本就在事务外)调用,两种情况都对。
 */
@Service
@Transactional
public class TenantPackageChangeApplier {

    private static final Logger log = LoggerFactory.getLogger(TenantPackageChangeApplier.class);

    private final TenantRepository tenantRepository;
    private final SysPackageRepository packageRepository;
    private final PackageChangeRecorder recorder;
    private final PermissionCacheService permissionCacheService;

    public TenantPackageChangeApplier(TenantRepository tenantRepository,
                                  SysPackageRepository packageRepository,
                                  PackageChangeRecorder recorder,
                                  PermissionCacheService permissionCacheService) {
        this.tenantRepository = tenantRepository;
        this.packageRepository = packageRepository;
        this.recorder = recorder;
        this.permissionCacheService = permissionCacheService;
    }

    /**
     * 把租户的套餐切到 {@code targetPackageId}:更新当前套餐 → 写变更记录 → 让新旧套餐的菜单缓存失效。
     *
     * <p><b>幂等</b>:租户已经是目标套餐时什么都不做(返回 false),到点任务靠这一条重复跑也安全。
     *
     * @return 是否真的发生了切换
     */
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
        // 差异只用于审计记录:它描述"这次切换带来了什么",不再用来回写角色
        Set<Long> oldMenus = recorder.menusOf(oldPackageId);
        Set<Long> newMenus = recorder.menusOf(targetPackageId);
        PackageChangeRecorder.Diff diff = recorder.diff(oldMenus, newMenus);

        tenant.setPackageId(targetPackageId);
        recorder.record(tenantId, triggerType, oldPackageId, targetPackageId, LocalDate.now(),
                diff.added(), diff.revoked());
        permissionCacheService.evictPackage(oldPackageId);
        permissionCacheService.evictPackage(targetPackageId);
        log.info("租户套餐已生效 tenantId={} {} -> {} 触发源={}", tenantId, oldPackageId, targetPackageId, triggerType);
        return true;
    }
}
