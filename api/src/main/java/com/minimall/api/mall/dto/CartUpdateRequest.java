package com.minimall.api.mall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 修改购物车条目(数量 / 勾选状态)。字段都可为空,只改传了的那个。
 */
public record CartUpdateRequest(
        @Min(value = 1, message = "数量至少为 1")
        @Max(value = 999, message = "单个 SKU 最多购买 999 件")
        Integer quantity,

        Integer selected) {
}
