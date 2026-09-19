package com.minimall.sys.api.dto;

/**
 * 创建租户结果(架构文档 4.7)。
 *
 * <p>{@code initialPassword} 只在"系统随机生成初始密码"时非空,且**只在这里返回一次**:
 * 不落库、不写日志、不提供再次查询的接口(架构文档 7.1.2)。
 */
public record TenantCreateResponse(
        Long tenantId,
        Long adminUserId,
        String adminUsername,
        String initialPassword,
        boolean mustChangePassword,
        Long defaultRoleId,
        Long rootDeptId
) {
}
