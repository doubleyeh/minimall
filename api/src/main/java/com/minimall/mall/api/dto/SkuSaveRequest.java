package com.minimall.mall.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * SKU 提交(商城设计文档 3.2)。
 *
 * @param id        有值表示更新已有 SKU;为空表示新增。**不提交的旧 SKU 会被置为停售而不是删除** ——
 *                  历史订单与售后要能回查到 SKU,物理删除会让这些关联断掉
 * @param specValues 该 SKU 的规格值组合(名称),如 ["红色", "XL"],必须能对应到本次提交的规格值
 */
public record SkuSaveRequest(
        Long id,

        @NotBlank(message = "SKU 编码不能为空")
        @Size(max = 64, message = "SKU 编码不能超过 64 个字符")
        String skuCode,

        @NotBlank(message = "SKU 名称不能为空")
        @Size(max = 128, message = "SKU 名称不能超过 128 个字符")
        String skuName,

        @Size(max = 255, message = "SKU 图片地址不能超过 255 个字符")
        String skuImage,

        @NotNull(message = "售价不能为空")
        @PositiveOrZero(message = "售价不能为负数")
        BigDecimal price,

        @PositiveOrZero(message = "成本价不能为负数")
        BigDecimal costPrice,

        @NotNull(message = "库存不能为空")
        @PositiveOrZero(message = "库存不能为负数")
        Integer stock,

        @PositiveOrZero(message = "重量不能为负数")
        BigDecimal weight,

        Integer status,

        List<String> specValues) {
}
