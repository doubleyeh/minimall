package com.minimall.api.sys.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 角色菜单授权请求(架构文档 5.2.1)。
 *
 * <p>服务端必须**再次校验**每个 menuId 都在"该租户套餐范围内的候选集"里,越界直接返回业务异常
 * (ErrorCode.MENU_OUT_OF_PACKAGE),不做静默过滤——静默过滤会让前端显示的授权与实际生效的不一致。
 *
 * <p>空列表表示"清空该角色的菜单授权",是合法请求,不要当成参数错误。
 */
public record RoleMenuGrantRequest(

        @NotNull(message = "菜单ID列表不能为null,清空授权请传空数组")
        List<Long> menuIds
) {
}
