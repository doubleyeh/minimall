package com.minimall.api.mall.dto;

import java.math.BigDecimal;

/**
 * 商品列表项(管理端)。
 *
 * <p>{@code costPrice} 之类只给后台看的字段不出现在这里;而 {@code availableStock}
 * 只在小程序端接口里出现 —— 管理端看 {@code totalStock} 是为了盘货,端上看可售库存是为了不超卖。
 */
public record GoodsView(
        Long id,
        Long categoryId,
        String categoryName,
        String goodsName,
        String goodsSubtitle,
        String mainImage,
        BigDecimal salePriceMin,
        BigDecimal salePriceMax,
        Integer totalStock,
        Integer saleCount,
        Integer status,
        Integer sortOrder) {
}
