package com.minimall.mall.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动视图(管理端)。
 *
 * @param frozenStock 该活动当前冻结在 SKU 上的总数量(仅待开始/进行中非 0),让运营看得到"这活动压了多少货"
 */
public record SeckillActivityView(
        Long id,
        String activityName,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer status,
        String remark,
        Integer sortOrder,
        int totalQuota,
        int totalSold,
        int frozenStock,
        List<Item> items) {

    /**
     * @param originalPrice SKU 原价,用来在页面上展示划线价与折扣
     * @param remaining     剩余可抢(配额 - 已占用)
     */
    public record Item(
            Long id,
            Long goodsId,
            String goodsName,
            String goodsImage,
            Long skuId,
            String skuCode,
            String skuName,
            BigDecimal originalPrice,
            BigDecimal seckillPrice,
            Integer seckillStock,
            Integer soldCount,
            Integer perCustomerLimit,
            Integer remaining) {
    }
}
