package com.minimall.api.sys.dto;

/**
 * 新增用户的结果(架构文档 7.1.2)。
 *
 * <p>{@code initialPassword} 有三种情况:客户端传了密码 → 为 null(用户自己知道);
 * 系统随机生成 → 返回一次明文并置 must_change_password = 1。无论哪种,**都不落库、不写日志**。
 */
public record UserCreateResponse(
        Long userId,
        String username,
        String initialPassword,
        boolean mustChangePassword
) {
}
