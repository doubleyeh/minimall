package com.minimall.sys.api.dto;

/**
 * 刷新结果(架构文档 7.1.3)。
 *
 * <p>{@code refreshToken} 是**新的一张**,与客户端提交的那张不同——刷新是一次性轮换,
 * 客户端必须用返回的这张覆盖本地保存的旧值;继续用旧值会在宽限期后触发重放判定,
 * 导致该用户被踢下线。
 *
 * <p>{@code expiresIn} 是访问令牌剩余秒数,客户端可以据此在到期前主动刷新
 * (但不要做成定时轮询:正常路径是"收到 401 再刷新一次")。
 */
public record RefreshTokenResponse(
        String token,
        String refreshToken,
        long expiresIn
) {
}
