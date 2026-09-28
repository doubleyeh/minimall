package com.minimall.mall.api.dto;

import java.util.List;

/**
 * 管理端客户详情:摘要 + 最近若干条积分与成长值流水。
 *
 * <p>两种流水都给,是因为**积分的消耗不改变成长值**(两者分账,见 3.11):
 * 客户说"我的积分少了"时,光看成长值流水是查不出来的,反之亦然。
 */
public record CustomerDetailView(
        CustomerView customer,
        List<PointsLogView> pointsLogs,
        List<GrowthLogView> growthLogs) {
}
