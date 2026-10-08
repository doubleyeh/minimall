package com.minimall.sys.service;

import com.minimall.sys.api.dto.PackageChangeView;

import java.time.LocalDate;
import java.util.List;

/**
 * 租户套餐变更(架构文档 4.8、4.8.1)。
 *
 * <p><b>生效粒度到"日"</b>:{@code effectiveDate <= 当天} 即立即生效;选未来日期则只登记,等定时任务到点应用。
 * <b>当前套餐 = 已生效记录里 id 最大的那条</b>(见 {@code SysTenantPackageChange} 的类注释)。
 */
public interface PackageChangeService {

    /**
     * 变更租户套餐。
     *
     * @param effectiveDate 生效日;为空或 {@code <= 当天} 一律视为立即生效
     */
    void changePackage(Long tenantId, Long packageId, LocalDate effectiveDate);

    /**
     * 到点应用(定时任务调用,必须**不在事务里**):逐租户把套餐切到"当前生效记录"里的套餐。
     *
     * <p>幂等:已经是目标套餐的租户会被跳过,所以重复跑安全。
     *
     * @return 实际发生切换的租户数
     */
    int applyDue();

    /** 某租户最近的若干条变更记录(变更历史抽屉用)。 */
    List<PackageChangeView> history(Long tenantId, int limit);
}
