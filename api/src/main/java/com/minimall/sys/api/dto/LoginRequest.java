package com.minimall.sys.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求(架构文档 7.1.1)。
 *
 * <p>为什么必须显式传 {@code tenantCode}:{@code sys_user} 的用户名唯一性是 (tenant_id, username) 联合唯一,
 * 同一用户名在不同租户下允许重复,只凭 username 查不到唯一用户,所以必须先确定租户。
 *
 * <p>password 是明文,由 HTTPS 保证传输安全,服务端不做额外加密,直接和 sys_user.password 的 BCrypt hash 比对。
 */
public record LoginRequest(

        @NotBlank(message = "租户编码不能为空")
        @Size(max = 32, message = "租户编码长度不能超过32")
        String tenantCode,

        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名长度不能超过64")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 128, message = "密码长度不合法")
        String password,

        /**
         * 设备标识,可选。**只用于日志与客服排查,不参与任何鉴权判断**(架构文档 7.1.3)。
         * 不传则由服务端生成一个并随响应返回,便于前端持久化后用于问题定位。
         */
        @Size(max = 64, message = "设备标识长度不能超过64")
        String deviceId
) {
}
