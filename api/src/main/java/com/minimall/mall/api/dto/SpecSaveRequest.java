package com.minimall.mall.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 商品规格提交(如"颜色:[红色, 蓝色]")。
 *
 * <p>规格值用**名称**而不是 ID 提交:管理端表单里用户写的就是名称,
 * 要求前端先调接口建规格值、再拿 ID 提交等于把一个事务拆成两次请求——
 * 中途失败会留下"有规格值但没有商品"的半成品。服务端在一次事务里按名称建好并关联。
 */
public record SpecSaveRequest(
        @NotBlank(message = "规格名不能为空")
        @Size(max = 32, message = "规格名不能超过 32 个字符")
        String specName,

        @NotEmpty(message = "规格值不能为空")
        List<String> values) {
}
