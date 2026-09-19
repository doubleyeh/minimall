package com.minimall.mall.service;

/**
 * 营销数据的维护动作(定时任务调用)。
 *
 * <p>单独立一个服务而不是把 SQL 写在调度类里:调度类只负责"什么时候执行、按租户循环",
 * 业务动作的落点仍然在 service 层,这样它和别的业务逻辑一样能被集成测试直接调用。
 */
public interface MarketingMaintenanceService {

    /**
     * 把已过期且未使用的优惠券记录置为"已过期"(3.10 的每日任务)。
     *
     * <p>注意这只是**兜底**:下单时仍会再判断一次有效期(3.10 要求的双保险)——
     * 定时任务的执行时机不可靠,只靠它会出现"券已过期但还能用"的窗口。
     *
     * @return 更新的记录数
     */
    int expireOutdatedCouponRecords();
}
