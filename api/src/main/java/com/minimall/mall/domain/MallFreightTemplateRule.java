package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 运费计费规则(商城设计文档 3.7)。
 *
 * <p>计算口径固定为"首件(重) + 续件(重)向上取整":
 * {@code fee = firstFee + ceil(max(0, 计量值 - firstUnit) / additionalUnit) * additionalFee}。
 * 向上取整是刻意的:不足一个续件单位也按一个算,否则会出现"加一件反而更便宜"的定价漏洞。
 *
 * <p>{@code region} 为 {@code ALL} 时是兜底规则:**匹配具体区域失败时用它**。
 * 没有兜底规则会导致"某些省份下单时算不出运费",所以保存模板时要校验至少有一条 ALL 规则。
 */
@Entity
@Table(name = "mall_freight_template_rule")
@Getter
@Setter
public class MallFreightTemplateRule extends BaseTenantEntity {

    /** 兜底区域标识:未匹配到具体区域时用它。 */
    public static final String REGION_ALL = "ALL";

    @Column(name = "template_id", nullable = false)
    private Long templateId;

    /** 适用区域:ALL 表示不限区域,否则按省份逗号分隔存储。 */
    @Column(name = "region", nullable = false, length = 255)
    private String region;

    /** 首件/首重。按件数时是件数,按重量时是 kg。 */
    @Column(name = "first_unit", nullable = false, precision = 10, scale = 3)
    private BigDecimal firstUnit;

    @Column(name = "first_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal firstFee;

    /** 续件/续重。 */
    @Column(name = "additional_unit", nullable = false, precision = 10, scale = 3)
    private BigDecimal additionalUnit;

    @Column(name = "additional_fee", nullable = false, precision = 10, scale = 2)
    private BigDecimal additionalFee;

    /** 满多少金额包邮,为空表示不设包邮门槛。 */
    @Column(name = "free_shipping_amount", precision = 10, scale = 2)
    private BigDecimal freeShippingAmount;
}
