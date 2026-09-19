package com.minimall.sys.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新访问令牌请求(架构文档 7.1.3)。
 *
 * <p>这个请求不带访问令牌:调用它时访问令牌通常**已经过期**,带过来也没有意义。
 * 租户上下文来自刷新令牌的载荷,由服务端在自己的上下文里恢复。
 */
public record RefreshTokenRequest(

        @NotBlank(message = "刷新令牌不能为空")
        String refreshToken
) {
}
