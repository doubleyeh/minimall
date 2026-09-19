package com.minimall.mall.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 会员等级新增/修改(商城设计文档 5.2 的开放项)。
 *
 * <p>{@code discountRate} 本期只存不算(晋升与折扣逻辑等业务规则明确后再实现)——
 * 半实现的折扣会真实地少收钱,所以宁可先不实现,也不写一个"大概对"的版本。
 */
public record MemberLevelSaveRequest(
        @NotBlank(message = "等级名称不能为空")
        @Size(max = 32, message = "等级名称不能超过 32 个字符")
        String levelName,

        @NotNull(message = "等级顺序不能为空")
        @Min(value = 0, message = "等级顺序不能为负数")
        Integer levelSort,

        @NotNull(message = "成长值门槛不能为空")
        @Min(value = 0, message = "成长值门槛不能为负数")
        Integer growthThreshold,

        BigDecimal discountRate,

        Integer status) {
}
