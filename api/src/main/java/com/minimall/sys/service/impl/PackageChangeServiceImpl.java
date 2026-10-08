package com.minimall.sys.service.impl;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.sys.api.dto.PackageChangeView;
import com.minimall.sys.domain.SysPackage;
import com.minimall.sys.domain.SysTenantPackageChange;
import com.minimall.sys.domain.Tenant;
import com.minimall.sys.domain.repository.SysPackageRepository;
import com.minimall.sys.domain.repository.SysTenantPackageChangeRepository;
import com.minimall.sys.domain.repository.TenantRepository;
import com.minimall.sys.service.PackageChangeService;
import com.minimall.sys.service.support.PackageChangeRecorder;
import com.minimall.sys.service.support.TenantTaskRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 租户套餐变更(架构文档 4.8、4.8.1)。
 *
 * <p>类级 {@code @Transactional} 是 {@code ArchitectureTest} 的硬约束(事务边界是租户过滤器生效的前提,4.2);
 * 但 {@link #applyDue()} 必须**在事务外**跑(它走 {@code TenantTaskRunner},每个租户要各自提交),
 * 所以那个方法用 {@code NOT_SUPPORTED} 把事务挂起。
 */
@Service
@Transactional
public class PackageChangeServiceImpl implements PackageChangeService {

    private static final Logger log = LoggerFactory.getLogger(PackageChangeServiceImpl.class);

    /** 系统任务的操作人:与商城侧任务同一约定,写进 create_by/update_by(否则租户内实体会被归属校验拦下)。 */
    private static final long SYSTEM_ACTOR_ID = 1L;
    private static final int DEFAULT_HISTORY_LIMIT = 20;
    private static final int MAX_HISTORY_LIMIT = 200;

    private final TenantRepository tenantRepository;
    private final SysPackageRepository packageRepository;
    private final SysTenantPackageChangeRepository changeRepository;
    private final PackageMenuSyncService packageMenuSyncService;
    private final PackageChangeRecorder recorder;
    private final TenantTaskRunner tenantTaskRunner;

    public PackageChangeServiceImpl(TenantRepository tenantRepository,
                                    SysPackageRepository packageRepository,
                                    SysTenantPackageChangeRepository changeRepository,
                                    PackageMenuSyncService packageMenuSyncService,
                                    PackageChangeRecorder recorder,
                                    TenantTaskRunner tenantTaskRunner) {
        this.tenantRepository = tenantRepository;
        this.packageRepository = packageRepository;
        this.changeRepository = changeRepository;
        this.packageMenuSyncService = packageMenuSyncService;
        this.recorder = recorder;
        this.tenantTaskRunner = tenantTaskRunner;
    }

    @Override
    @Transactional
    public void changePackage(Long tenantId, Long packageId, LocalDate effectiveDate) {
        if (packageId == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "目标套餐不能为空");
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (Objects.equals(tenant.getPackageId(), packageId)) {
            // 换同套餐是 no-op:不写记录,免得变更历史里全是噪音
            return;
        }
        if (!packageRepository.existsById(packageId)) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "目标套餐不存在");
        }

        LocalDate today = LocalDate.now();
        if (effectiveDate != null && effectiveDate.isAfter(today)) {
            // 预约:只登记。差异按"当前套餐 → 目标套餐"预演,供变更历史展示;
            // 到点真正应用时同步用的是那一刻重算的实际差异(见 PackageMenuSyncService#applyPackageChange)
            PackageChangeRecorder.Diff diff = recorder.diff(recorder.menusOf(tenant.getPackageId()),
                    recorder.menusOf(packageId));
            recorder.record(tenantId, SysTenantPackageChange.TRIGGER_TENANT_SWITCH, tenant.getPackageId(), packageId,
                    effectiveDate, diff.added(), diff.revoked());
            log.info("套餐变更已登记(预约生效) tenantId={} {} -> {} 生效日={}",
                    tenantId, tenant.getPackageId(), packageId, effectiveDate);
            return;
        }
        // 选当天(或没传)= 立即生效
        packageMenuSyncService.applyPackageChange(tenantId, packageId,
                SysTenantPackageChange.TRIGGER_TENANT_SWITCH);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int applyDue() {
        LocalDate today = LocalDate.now();
        int[] applied = {0};
        TenantTaskRunner.RunResult result = tenantTaskRunner.runForEachTenant("套餐变更生效", SYSTEM_ACTOR_ID,
                tenantId -> changeRepository
                        // 当前生效记录 = 已生效里 id 最大的那条(先按生效日过滤,再按写入顺序取最新)
                        .findFirstByTenantIdAndEffectiveDateLessThanEqualOrderByIdDesc(tenantId, today)
                        .ifPresent(record -> {
                            if (packageMenuSyncService.applyPackageChange(tenantId, record.getNewPackageId(),
                                    SysTenantPackageChange.TRIGGER_SCHEDULED_APPLY)) {
                                applied[0]++;
                            }
                        }));
        if (applied[0] > 0) {
            log.info("套餐变更到点生效:已应用 {} 个租户", applied[0]);
        }
        if (!result.allSucceeded()) {
            // 执行历史按"任务有没有抛异常"记结果:只 warn 会把"有租户没生效"记成成功
            throw new IllegalStateException("任务「套餐变更生效」部分租户失败,该批次需重跑:" + result.failedTenantIds());
        }
        return applied[0];
    }

    @Override
    @Transactional(readOnly = true)
    public List<PackageChangeView> history(Long tenantId, int limit) {
        int size = limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
        List<SysTenantPackageChange> records = changeRepository
                .findByTenantIdOrderByIdDesc(tenantId, PageRequest.of(0, size));
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Long, String> packageNames = packageNames(records);
        Long currentRecordId = changeRepository
                .findFirstByTenantIdAndEffectiveDateLessThanEqualOrderByIdDesc(tenantId, LocalDate.now())
                .map(SysTenantPackageChange::getId)
                .orElse(null);
        return records.stream()
                .map(record -> new PackageChangeView(record.getId(), record.getCreateTime(),
                        record.getTriggerType(), triggerText(record.getTriggerType()),
                        record.getOldPackageId(), packageNames.get(record.getOldPackageId()),
                        record.getNewPackageId(), packageNames.get(record.getNewPackageId()),
                        record.getEffectiveDate(),
                        !record.getEffectiveDate().isAfter(LocalDate.now()),
                        Objects.equals(record.getId(), currentRecordId),
                        PackageChangeRecorder.parseIds(record.getAddedMenuIds()),
                        PackageChangeRecorder.parseIds(record.getRevokedMenuIds())))
                .toList();
    }

    private Map<Long, String> packageNames(List<SysTenantPackageChange> records) {
        List<Long> ids = records.stream()
                .flatMap(record -> java.util.stream.Stream.of(record.getOldPackageId(), record.getNewPackageId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> names = new HashMap<>();
        packageRepository.findAllById(ids).forEach(pkg -> names.put(pkg.getId(), pkg.getPackageName()));
        return names;
    }

    private String triggerText(int triggerType) {
        return switch (triggerType) {
            case SysTenantPackageChange.TRIGGER_TENANT_SWITCH -> "变更套餐";
            case SysTenantPackageChange.TRIGGER_PACKAGE_MENU_EDIT -> "平台调整套餐菜单";
            case SysTenantPackageChange.TRIGGER_SCHEDULED_APPLY -> "预约到点生效";
            default -> "未知";
        };
    }
}
