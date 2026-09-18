package com.minimall.api.sys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增/修改套餐请求(架构文档 4.7)。
 *
 * <p>套餐只定义"这个租户能用哪些菜单/功能"(entitlement 天花板),与角色权限是两层概念:
 * 套餐决定天花板,角色决定天花板以内怎么分给具体用户。
 */
public record PackageSaveRequest(

        @NotBlank(message = "套餐名称不能为空")
        @Size(max = 64, message = "套餐名称长度不能超过64")
        String packageName,

        @Size(max = 255, message = "备注长度不能超过255")
        String remark,

        /** 0-禁用 1-正常。禁用后不可再被新租户选用,不影响已绑定该套餐的租户 */
        Integer status
) {
}
