package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 运费模板主表(商城设计文档 3.7)。
 *
 * <p>{@code chargeType} 决定计费口径(按件数/按重量),它属于**模板级**而不是规则级:
 * 同一个模板下所有地区的规则必须用同一种口径,否则"首件"和"首重"混在一起无法计算。
 */
@Entity
@Table(name = "mall_freight_template")
@Getter
@Setter
public class MallFreightTemplate extends BaseTenantEntity {

    /** 按件数。 */
    public static final int CHARGE_BY_QUANTITY = 1;
    /** 按重量。 */
    public static final int CHARGE_BY_WEIGHT = 2;

    @Column(name = "template_name", nullable = false, length = 64)
    private String templateName;

    /** 1-按件数 2-按重量。 */
    @Column(name = "charge_type", nullable = false)
    private Integer chargeType;
}
