package com.minimall.sys.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增/修改菜单请求(架构文档 5.1、5.2)。
 *
 * <p>平台级操作:<b>只有平台超管能维护菜单</b>——菜单是全平台统一的,租户角色只能勾选授权,不能自建
 * (5.1)。对应接口的 perm_code 挂在 {@code is_platform = 1} 的菜单上,普通租户在数据库层面就取不到(4.10)。
 *
 * <p>{@code permCode} 必须全局唯一(建表脚本 uk_perm_code),否则 {@code @SaCheckPermission} 的语义直接失效:
 * 两个不同功能的菜单共用一个权限码时,授权其中一个等于顺带授权另一个。
 */
public record MenuSaveRequest(

        @NotNull(message = "上级菜单不能为空,顶级菜单请传0")
        Long parentId,

        @NotBlank(message = "菜单名称不能为空")
        @Size(max = 64, message = "菜单名称长度不能超过64")
        String menuName,

        /** 1-目录 2-页面 3-按钮 */
        @NotNull(message = "菜单类型不能为空")
        Integer menuType,

        @Size(max = 128, message = "路由地址长度不能超过128")
        String routePath,

        /** 格式 模块:资源:操作(如 order:delete);menuType=3 时必填,由服务端校验 */
        @Pattern(regexp = "^$|^[a-z][a-z0-9]*(:[a-z][a-z0-9]*){1,3}$",
                message = "权限标识格式应为 模块:资源:操作,如 order:delete")
        @Size(max = 128, message = "权限标识长度不能超过128")
        String permCode,

        @Size(max = 64, message = "图标长度不能超过64")
        String icon,

        @Min(value = 0, message = "排序值不能为负")
        Integer sortOrder,

        Integer status,

        /**
         * 是否平台专用。这是一个"提高防线"的开关:标了 1 的菜单不允许进入任何套餐(5.2.1),
         * 因此普通租户的授权候选集里永远不会出现它。新增平台管理类菜单时必须显式传 1。
         */
        Boolean isPlatform
) {
}
