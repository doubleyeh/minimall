package com.minimall.sys.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建/修改角色请求(架构文档 5.3)。
 *
 * <p>{@code dataScope} 取值 1-仅本人 / 2-本部门 / 3-本部门及以下 / 4-自定义部门 / 5-全部。
 * 注意 5.3 的多角色合并规则是"取最大值(最宽)":给用户多挂一个宽范围角色会放宽其数据可见范围,
 * 所以新建角色的默认档位应该往窄的选,不要默认给 5(建租户流程里的默认管理员角色给 5 是刻意的)。
 *
 * <p>{@code deptIds} 只在 {@code dataScope = 4} 时有意义,由服务端校验"为 4 时必须非空"。
 */
public record RoleCreateRequest(

        @NotBlank(message = "角色标识不能为空")
        @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{1,63}$", message = "角色标识需为2-64位字母/数字/下划线,且以字母开头")
        String roleKey,

        @NotBlank(message = "角色名称不能为空")
        @Size(max = 64, message = "角色名称长度不能超过64")
        String roleName,

        @NotNull(message = "数据权限范围不能为空")
        @Min(value = 1, message = "数据权限范围取值为1-5")
        @Max(value = 5, message = "数据权限范围取值为1-5")
        Integer dataScope,

        List<Long> deptIds,

        Integer status
) {
}
