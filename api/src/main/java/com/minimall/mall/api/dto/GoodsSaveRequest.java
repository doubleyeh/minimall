package com.minimall.mall.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 商品新增/修改请求(商城设计文档 3.2)。
 *
 * <p>商品、轮播图、规格、SKU 一次性提交:它们的汇总字段({@code salePriceMin}/{@code salePriceMax}/
 * {@code totalStock})必须与 SKU 在同一事务内保持一致。拆成多次请求,任何一次失败都会让
 * 列表页显示的价格与实际 SKU 对不上。
 *
 * @param images 轮播图 URL,可为空(为空则详情页只展示主图)
 */
public record GoodsSaveRequest(
        @NotNull(message = "商品分类不能为空")
        Long categoryId,

        @NotBlank(message = "商品名称不能为空")
        @Size(max = 128, message = "商品名称不能超过 128 个字符")
        String goodsName,

        @Size(max = 255, message = "副标题不能超过 255 个字符")
        String goodsSubtitle,

        @NotBlank(message = "商品主图不能为空")
        @Size(max = 255, message = "主图地址不能超过 255 个字符")
        String mainImage,

        String detailContent,

        Long freightTemplateId,

        Integer sortOrder,

        Integer status,

        List<String> images,

        // @Valid 标在**类型参数**上而不是容器上:标在容器上(Hibernate Validator 会报 HV000271)
        // 已废弃,而且语义不同 —— 标在类型参数上表示"逐个元素校验",这才是我们要的
        List<@Valid SpecSaveRequest> specs,

        @NotEmpty(message = "至少需要一个 SKU")
        List<@Valid SkuSaveRequest> skus) {
}
