package com.minimall.api.mall.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 满减活动(管理端)。
 */
public record PromotionView(
        Long id,
        String activityName,
        String reductionRule,
        Integer scopeType,
        List<Long> scopeIds,
        LocalDateTime validStartTime,
        LocalDateTime validEndTime,
        Integer status) {
}
