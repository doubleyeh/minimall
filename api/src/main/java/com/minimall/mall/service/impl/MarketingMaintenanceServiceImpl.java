package com.minimall.mall.service.impl;

import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.repository.MallCouponRecordRepository;
import com.minimall.mall.service.MarketingMaintenanceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 营销数据维护实现。
 */
@Service
@Transactional
public class MarketingMaintenanceServiceImpl implements MarketingMaintenanceService {

    private final MallCouponRecordRepository couponRecordRepository;

    public MarketingMaintenanceServiceImpl(MallCouponRecordRepository couponRecordRepository) {
        this.couponRecordRepository = couponRecordRepository;
    }

    @Override
    public int expireOutdatedCouponRecords() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            // 逐租户任务必须带租户执行;没带就说明调度侧写错了,直接失败比"清理了所有租户的数据"安全
            throw new IllegalStateException("过期优惠券清理必须在租户上下文中执行");
        }
        return couponRecordRepository.expireOutdated(LocalDateTime.now(), tenantId);
    }
}
