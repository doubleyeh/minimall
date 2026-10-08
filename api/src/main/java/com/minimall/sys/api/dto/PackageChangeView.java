package com.minimall.sys.api.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 一条套餐变更记录(管理端"变更历史"用)。
 *
 * @param effectiveDate 生效日(只到日);{@code <= 当天} 即已生效
 * @param effective     是否已生效
 * @param current       是否**当前生效**的那条(已生效记录里 id 最大)
 */
public record PackageChangeView(
        Long id,
        LocalDateTime createTime,
        int triggerType,
        String triggerText,
        Long oldPackageId,
        String oldPackageName,
        Long newPackageId,
        String newPackageName,
        LocalDate effectiveDate,
        boolean effective,
        boolean current,
        List<Long> addedMenuIds,
        List<Long> revokedMenuIds) {
}
