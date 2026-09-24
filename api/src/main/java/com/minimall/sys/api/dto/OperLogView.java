package com.minimall.sys.api.dto;

import java.time.LocalDateTime;

/**
 * 操作日志的查询视图(架构文档 7.2)。
 *
 * <p>只读:日志不允许改与删,所以没有对应的保存请求。
 *
 * @param status 0-失败 1-成功
 */
public record OperLogView(
        Long id,
        Long tenantId,
        Long userId,
        String module,
        String permCode,
        String method,
        String requestParams,
        Integer status,
        String errorMsg,
        String ip,
        String traceId,
        LocalDateTime createTime
) {
}
