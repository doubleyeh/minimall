package com.minimall.sys.api.dto;

import java.time.LocalDateTime;

/**
 * 修改租户有效期(架构文档 4.11)。
 *
 * @param expireTime 新的到期时间;传 {@code null} 表示改为不过期。
 *                   LocalDateTime 不带时区,业务时区固定 Asia/Shanghai
 */
public record TenantExpireTimeRequest(
        LocalDateTime expireTime
) {
}
