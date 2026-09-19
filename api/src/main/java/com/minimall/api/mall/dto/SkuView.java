package com.minimall.api.mall.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * SKU 视图。
 *
 * <p>{@code costPrice} <b>只在管理端接口返回</b>。小程序端另有一套不含成本价的视图
 * ({@code ClientSkuView})—— 把成本价暴露给端上,等于把毛利率送给竞争对手,
 * 这不是"前端不渲染"能挡住的(响应体里就是有)。
 *
 * @param availableStock 可售库存 = {@code stock - lockedStock}(3.2),端上下单校验与展示都用它
 */
public record SkuView(
        Long id,
        String skuCode,
        String skuName,
        String skuImage,
        BigDecimal price,
        BigDecimal costPrice,
        Integer stock,
        Integer lockedStock,
        Integer availableStock,
        BigDecimal weight,
        Integer status,
        List<String> specValues) {
}
