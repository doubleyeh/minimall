package com.minimall.api.mall.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品详情(管理端编辑用):含轮播图、规格与全部 SKU。
 *
 * <p>编辑页需要完整结构才能做回显,所以这里一次给全 —— 但端上(小程序)用的是另一套更瘦的 View,
 * 不会带上 {@code costPrice} 这类内部字段(见 SkuView 的说明)。
 */
public record GoodsDetailView(
        Long id,
        Long categoryId,
        String goodsName,
        String goodsSubtitle,
        String mainImage,
        String detailContent,
        Long freightTemplateId,
        BigDecimal salePriceMin,
        BigDecimal salePriceMax,
        Integer totalStock,
        Integer saleCount,
        Integer status,
        Integer sortOrder,
        List<String> images,
        List<SpecView> specs,
        List<SkuView> skus) {

    /** 规格名与其取值。 */
    public record SpecView(Long id, String specName, Integer sortOrder, List<SpecValueView> values) {
    }

    /** 规格值。 */
    public record SpecValueView(Long id, String specValue, Integer sortOrder) {
    }
}
