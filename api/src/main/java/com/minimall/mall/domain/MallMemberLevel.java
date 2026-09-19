package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 会员等级定义(商城设计文档 5.2 的开放项)。
 *
 * <p><b>本期只建字段,不做晋升与折扣计算</b>:等级折扣与优惠券/满减的叠加顺序尚未确定,
 * 现在实现出来的规则大概率要返工,而"半实现的折扣"会真实地少收钱。字段先留着,
 * 等业务规则明确后再补计算逻辑(开放项 2)。
 */
@Entity
@Table(name = "mall_member_level")
@Getter
@Setter
public class MallMemberLevel extends BaseTenantEntity {

    @Column(name = "level_name", nullable = false, length = 32)
    private String levelName;

    /** 等级顺序,数字越大等级越高。 */
    @Column(name = "level_sort", nullable = false)
    private Integer levelSort;

    /** 达到该成长值自动晋升到此等级。 */
    @Column(name = "growth_threshold", nullable = false)
    private Integer growthThreshold;

    /** 等级折扣率,如 0.95 表示 9.5 折;本期字段先建,计算逻辑不实现。 */
    @Column(name = "discount_rate", precision = 3, scale = 2)
    private BigDecimal discountRate;

    @Column(name = "status", nullable = false)
    private Integer status;
}
