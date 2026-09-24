package com.minimall.sys.api.dto;

import java.time.LocalDateTime;

/**
 * 操作日志的查询条件(全部可选,不传即不过滤)。
 *
 * @param tenantId  按租户过滤。只有超管有意义:普通租户用户的查询会被租户过滤再收一次,只能看到自己的
 * @param startTime 起始时间(含);为空表示不限
 * @param endTime   结束时间(含);为空表示不限
 */
public record OperLogQuery(
        Long tenantId,
        Long userId,
        String module,
        Integer status,
        LocalDateTime startTime,
        LocalDateTime endTime,
        int pageNo,
        int pageSize
) {
}
