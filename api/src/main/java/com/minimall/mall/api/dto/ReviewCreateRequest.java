package com.minimall.mall.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提交评价(商城设计文档 2)。
 *
 * <p>只提交 {@code orderItemId} 而不提交 {@code goodsId}:商品从订单明细推导出来,
 * 由客户端指定商品等于允许"评一个没买过的商品"。
 */
public record ReviewCreateRequest(
        @NotNull(message = "订单明细不能为空")
        Long orderItemId,

        @NotNull(message = "评分不能为空")
        @Min(value = 1, message = "评分最低 1 星")
        @Max(value = 5, message = "评分最高 5 星")
        Integer rating,

        @Size(max = 500, message = "评价内容不能超过 500 个字符")
        String content,

        List<String> images,

        Boolean anonymous) {
}
