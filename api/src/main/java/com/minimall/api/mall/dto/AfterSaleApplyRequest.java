package com.minimall.api.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 申请售后(商城设计文档 3.9)。
 *
 * <p>{@code refundAmount} 不得超过订单明细行的 {@code totalAmount},商家处理时只能**下调** ——
 * 这条约束在服务端强制(端上可以改请求体,不能靠前端限制)。
 */
public record AfterSaleApplyRequest(
        @NotNull(message = "订单明细不能为空")
        Long orderItemId,

        @NotNull(message = "售后类型不能为空")
        Integer afterSaleType,

        @NotBlank(message = "申请原因不能为空")
        @Size(max = 64, message = "申请原因不能超过 64 个字符")
        String applyReason,

        @Size(max = 500, message = "问题描述不能超过 500 个字符")
        String applyDesc,

        @NotNull(message = "退款金额不能为空")
        @Positive(message = "退款金额必须大于 0")
        BigDecimal refundAmount,

        /** 申请凭证图片。 */
        List<String> images) {
}
