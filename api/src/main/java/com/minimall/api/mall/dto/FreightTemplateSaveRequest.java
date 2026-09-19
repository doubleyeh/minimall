package com.minimall.api.mall.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 运费模板新增/修改(商城设计文档 3.7)。
 *
 * <p>模板与规则一次性提交:规则的集合就是模板的完整定义,分两次提交会出现"模板存在但没有任何规则"
 * 的中间态,而那时下单算运费会直接落到"没有可用规则 → 运费 0"的兜底上。
 */
public record FreightTemplateSaveRequest(
        @NotBlank(message = "模板名称不能为空")
        @Size(max = 64, message = "模板名称不能超过 64 个字符")
        String templateName,

        @NotNull(message = "计费方式不能为空")
        Integer chargeType,

        // @Valid 标在类型参数上(逐个元素校验)。标在容器上已废弃,见 GoodsSaveRequest 的同类说明
        @NotEmpty(message = "至少需要一条运费规则")
        List<@Valid Rule> rules) {

    /**
     * 一条区域规则。
     *
     * @param region 适用区域:{@code ALL} 表示不限区域(兜底),否则按省份逗号分隔,
     *               如 {@code 广东省,广西壮族自治区}
     */
    public record Rule(
            @NotBlank(message = "适用区域不能为空")
            String region,

            @NotNull(message = "首件/首重不能为空")
            @PositiveOrZero(message = "首件/首重不能为负数")
            BigDecimal firstUnit,

            @NotNull(message = "首费不能为空")
            @PositiveOrZero(message = "首费不能为负数")
            BigDecimal firstFee,

            @NotNull(message = "续件/续重步长不能为空")
            @PositiveOrZero(message = "续件/续重步长不能为负数")
            BigDecimal additionalUnit,

            @NotNull(message = "续费不能为空")
            @PositiveOrZero(message = "续费不能为负数")
            BigDecimal additionalFee,

            @PositiveOrZero(message = "包邮门槛不能为负数")
            BigDecimal freeShippingAmount) {
    }
}
