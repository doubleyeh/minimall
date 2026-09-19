package com.minimall.mall.api.dto;

/**
 * 小程序登录响应。
 *
 * <p>不带 {@code openid}:端上不需要它,返回它只会让 openid 出现在日志与前端存储里。
 * 前端唯一要做的事是保存 {@code token} 并在过期后重新调用登录接口(3.1)。
 *
 * @param token     客户端令牌,后续请求按 {@code Authorization: Bearer <token>} 携带
 * @param expiresIn 有效期(秒),前端据此决定何时静默重登
 * @param isNew     本次是否新建了客户记录(前端可据此决定是否引导完善资料)
 */
public record ClientLoginResponse(
        String token,
        long expiresIn,
        boolean isNew,
        Long customerId,
        String nickname,
        String avatarUrl) {
}
