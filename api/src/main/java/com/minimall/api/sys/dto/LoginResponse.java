package com.minimall.api.sys.dto;

import java.util.List;

/**
 * 登录成功响应(架构文档 7.1.1)。
 *
 * <p>{@code menus}/{@code permCodes} 是"登录那一刻"的快照,前端用来渲染动态路由与按钮;
 * 权限变更后靠重新登录或调 {@code GET /auth/permissions} 刷新(架构文档 5.5)。
 * 服务端不认识这份快照,校验始终以服务端为准。
 *
 * <p>ID 字段都是 Long:全局 Long->String 序列化在 infra 的 Jackson 配置里统一处理,
 * 避免雪花 ID 超过 JS 的 Number.MAX_SAFE_INTEGER 造成前端精度丢失。
 */
public record LoginResponse(
        String token,
        /**
         * 刷新令牌(架构文档 7.1.3)。客户端**必须持久化**它:访问令牌只有 30 分钟,
         * 过期后靠它换新的令牌对;丢了就只能重新登录。
         */
        String refreshToken,
        /** 回显设备标识,仅用于排查 */
        String deviceId,
        Long userId,
        Long tenantId,
        boolean isSuperUser,
        boolean mustChangePassword,
        String nickname,
        List<String> menus,
        List<String> permCodes
) {
}
