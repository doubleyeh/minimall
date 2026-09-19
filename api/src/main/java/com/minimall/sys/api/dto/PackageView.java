package com.minimall.sys.api.dto;

import java.time.LocalDateTime;

/**
 * 套餐列表项(架构文档 4.7)。
 *
 * <p>{@code tenantCount} 用来在界面上提示"该套餐还有多少租户在用"——
 * 有租户在用的套餐只能禁用不能删除(5.6)。
 */
public record PackageView(
        Long id,
        String packageName,
        String remark,
        Integer status,
        Long menuCount,
        Long tenantCount,
        LocalDateTime createTime
) {
}
