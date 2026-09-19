package com.minimall.api.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 商品分类新增/修改请求(商城设计文档 3.2)。
 *
 * @param parentId 上级分类 ID,顶层传 0 或不传
 */
public record CategorySaveRequest(
        Long parentId,

        @NotBlank(message = "分类名称不能为空")
        @Size(max = 64, message = "分类名称不能超过 64 个字符")
        String categoryName,

        @Size(max = 255, message = "图标地址不能超过 255 个字符")
        String icon,

        Integer sortOrder,

        Integer status) {
}
