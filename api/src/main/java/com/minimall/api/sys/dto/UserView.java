package com.minimall.api.sys.dto;

import java.time.LocalDateTime;

/**
 * 用户列表/详情项。
 *
 * <p>两点约定:
 * <ul>
 *   <li>{@code phone} 在服务端就已脱敏(架构文档 7.1:敏感字段返回值和日志都要脱敏),
 *       不要在前端做脱敏——那样明文仍然出了服务器</li>
 *   <li>不返回 {@code password}、{@code isSuper} 等技术字段。{@code is_super} 只能由种子数据/运维脚本设置,
 *       接口既不接受也不暴露(架构文档 4.10)</li>
 * </ul>
 */
public record UserView(
        Long id,
        String username,
        String nickname,
        String phone,
        Long deptId,
        String deptName,
        Integer status,
        LocalDateTime lockTime,
        boolean mustChangePassword,
        LocalDateTime createTime
) {
}
