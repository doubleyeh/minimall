package com.minimall.api.mall.dto;

import java.math.BigDecimal;

/**
 * 小程序端商品列表项。
 *
 * <p>列表页只需要展示所需的最小字段集合:端上列表通常一次返回几十条,
 * 每条多带几个大字段(详情富文本)会让首屏明显变慢。
 */
public record ClientGoodsView(
        Long id,
        String goodsName,
        String goodsSubtitle,
        String mainImage,
        BigDecimal salePriceMin,
        BigDecimal salePriceMax,
        Integer saleCount) {
}
