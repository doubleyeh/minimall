package com.minimall.mall.api.dto;

/**
 * 库存预警行(管理端)。
 *
 * <p>四个库存数字一起给:{@code stock} 是实际库存,{@code lockedStock} 是下单未支付锁定的,
 * {@code frozenStock} 是被已发布秒杀活动冻结的,预警判据是**可售**
 * ({@code stock - lockedStock - frozenStock})。少给一个数字,运营看到"库存 20 也在预警里"
 * 就会以为系统算错了。
 */
public record StockWarnView(
        Long skuId,
        Long goodsId,
        String goodsName,
        String mainImage,
        String skuCode,
        String skuName,
        Integer stock,
        Integer lockedStock,
        Integer frozenStock,
        Integer availableStock) {
}
