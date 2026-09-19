package com.minimall.sys.api.dto;

/**
 * 超管重置用户密码的结果(架构文档 7.1.2)。
 *
 * <p>{@code initialPassword} 只在本次响应里返回一次,不落库、不写日志;
 * 同时置 must_change_password = 1,用户下次登录被强制改密。
 */
public record UserResetPasswordResponse(
        Long userId,
        String username,
        String initialPassword,
        boolean mustChangePassword
) {
}
