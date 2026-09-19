package com.minimall.sys.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增/修改部门请求(架构文档 5.1、5.6)。
 *
 * <p>{@code ancestors} **不由客户端提交**:它由服务端根据父部门的祖级链拼出来,
 * 客户端能改它等于能伪造层级关系,进而绕过"本部门及以下"的数据权限(5.3)。
 */
public record DeptSaveRequest(

        /** 0 表示根部门。修改时不允许把自己挂到自己的子孙下面(服务端校验,否则部门树成环) */
        @NotNull(message = "上级部门不能为空,根部门请传0")
        Long parentId,

        @NotBlank(message = "部门名称不能为空")
        @Size(max = 64, message = "部门名称长度不能超过64")
        String deptName,

        @Min(value = 0, message = "排序值不能为负")
        Integer sortOrder,

        Integer status
) {
}
