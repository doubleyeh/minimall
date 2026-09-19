package com.minimall.sys.api.dto;

import java.time.LocalDateTime;

/**
 * 租户列表项(架构文档 4.6.1:{@code tenant} 是平台级表,不受租户过滤,天然只能被平台超管查询)。
 */
public record TenantView(
        Long id,
        String tenantCode,
        String tenantName,
        Integer status,
        Long packageId,
        String packageName,
        LocalDateTime expireTime,
        LocalDateTime createTime
) {
}
