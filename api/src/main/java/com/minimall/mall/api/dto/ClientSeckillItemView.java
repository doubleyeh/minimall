package com.minimall.mall.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀商品项视图(客户端)。
 *
 * @param remaining 剩余可抢。列表页读数据库快照,详情页优先读 Redis 计数器
 * @param bought    当前客户已购件数(未登录或未买过为 0),用于"每人限购 N 件,你已购 M 件"
 */
public record ClientSeckillItemView(
        Long itemId,
        Long activityId,
        String activityName,
        Long goodsId,
        String goodsName,
        String goodsImage,
        Long skuId,
        String skuName,
        BigDecimal originalPrice,
        BigDecimal seckillPrice,
        Integer totalQuota,
        Integer soldCount,
        Integer remaining,
        Integer perCustomerLimit,
        Integer bought,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer status) {
}
