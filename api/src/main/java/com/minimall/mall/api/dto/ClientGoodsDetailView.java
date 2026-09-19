package com.minimall.mall.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 小程序端商品详情。
 *
 * <p>规格用"规格名 + 可选值"表达,SKU 列表带上各自的规格值组合 —— 端上据此做
 * "选了颜色再筛尺码"的联动,以及"这个组合有没有货"的判断(看 {@code availableStock})。
 *
 * @param totalStock 可售总库存(所有启用 SKU 的 {@code stock - lockedStock} 之和)。
 *                   列表与详情都按可售库存展示,不能直接展示 {@code stock}(3.2)
 */
public record ClientGoodsDetailView(
        Long id,
        String goodsName,
        String goodsSubtitle,
        String mainImage,
        String detailContent,
        BigDecimal salePriceMin,
        BigDecimal salePriceMax,
        Integer totalStock,
        Integer saleCount,
        List<String> images,
        List<SpecGroup> specs,
        List<ClientSkuView> skus) {

    /** 一组规格(如"颜色")及其可选值。 */
    public record SpecGroup(String specName, List<String> values) {
    }
}
