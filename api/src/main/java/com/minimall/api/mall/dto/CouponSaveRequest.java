package com.minimall.api.mall.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券新增/修改(商城设计文档 3.10)。
 *
 * <p>三种类型用一组字段表达:{@code couponType = 1/3} 用 {@code discountAmount},
 * {@code = 2} 用 {@code discountRate}。**服务端会按类型校验另一个字段是否为空**,
 * 不做"留空就按 0 处理"的兜底 —— 那会让配错的券变成"减 0 元",用户领了却没有任何效果。
 */
public record CouponSaveRequest(
        @NotBlank(message = "优惠券名称不能为空")
        @Size(max = 64, message = "优惠券名称不能超过 64 个字符")
        String couponName,

        @NotNull(message = "优惠券类型不能为空")
        Integer couponType,

        @PositiveOrZero(message = "减免金额不能为负数")
        BigDecimal discountAmount,

        BigDecimal discountRate,

        @NotNull(message = "使用门槛不能为空")
        @PositiveOrZero(message = "使用门槛不能为负数")
        BigDecimal minOrderAmount,

        @NotNull(message = "发放总量不能为空")
        @Min(value = 1, message = "发放总量至少为 1")
        Integer totalCount,

        @NotNull(message = "每人限领数量不能为空")
        @Min(value = 1, message = "每人限领数量至少为 1")
        Integer perCustomerLimit,

        @NotNull(message = "有效期开始时间不能为空")
        LocalDateTime validStartTime,

        @NotNull(message = "有效期结束时间不能为空")
        LocalDateTime validEndTime,

        Integer status) {
}
