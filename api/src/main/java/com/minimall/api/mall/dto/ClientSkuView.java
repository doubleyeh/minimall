package com.minimall.api.mall.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 小程序端 SKU 视图。
 *
 * <p><b>刻意不含 {@code costPrice}</b>:成本价属于商家内部数据,只在管理端接口返回
 * (见 {@link SkuView})。端上拿到成本价就等于把毛利率公开 —— 这不是"前端不显示"挡得住的。
 *
 * @param availableStock 可售库存 = {@code stock - lockedStock},端上展示与下单校验都用它
 */
public record ClientSkuView(
        Long id,
        String skuName,
        String skuImage,
        BigDecimal price,
        Integer availableStock,
        List<String> specValues) {
}
