package com.minimall.api.mall.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 运费模板(管理端)。
 */
public record FreightTemplateView(
        Long id,
        String templateName,
        Integer chargeType,
        List<Rule> rules) {

    /** 规则明细。 */
    public record Rule(
            Long id,
            String region,
            BigDecimal firstUnit,
            BigDecimal firstFee,
            BigDecimal additionalUnit,
            BigDecimal additionalFee,
            BigDecimal freeShippingAmount) {
    }
}
