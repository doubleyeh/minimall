package com.minimall.mall.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 加入购物车。
 *
 * <p>数量上限 999 是刻意的:端上一个 SKU 买上千件几乎一定是误操作(或刷接口),
 * 而它会让"可售库存"校验直接失败,用户看到的却是"库存不足"这种莫名其妙的提示。
 */
public record CartAddRequest(
        @NotNull(message = "SKU 不能为空") Long skuId,

        @NotNull(message = "数量不能为空")
        @Min(value = 1, message = "数量至少为 1")
        @Max(value = 999, message = "单个 SKU 最多购买 999 件")
        Integer quantity) {
}
