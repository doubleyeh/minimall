package com.minimall.sys.api.dto;

import java.time.LocalDateTime;

/**
 * 角色列表项(架构文档 8.1 用例 8:租户 A 只能看到自己的角色,靠 tenant_id 等值过滤,没有平台模板例外)。
 */
public record RoleView(
        Long id,
        String roleKey,
        String roleName,
        Integer dataScope,
        Integer isDefault,
        Integer status,
        LocalDateTime createTime
) {
}
