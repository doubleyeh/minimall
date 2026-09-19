package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 满减活动(商城设计文档 3.5、3.10)。
 *
 * <p>{@code reductionRule} 存 JSON 阶梯(如 {@code [{"amount":100,"reduce":10}, ...]}),
 * 应用层解析后**按金额从高到低匹配第一个满足的阶梯** —— 直接取第一个满足的而不是取最大的,
 * 会让阶梯顺序写反时少减钱;解析时按金额排序可以避免这个问题。
 *
 * <p>满减活动之间**不叠加**:一个订单只取"满足条件里减免金额最大的一个活动"(3.5)。
 * 这不是实现偷懒,而是业务上明确的规则 —— 叠加规则会让优惠金额难以预期。
 */
@Entity
@Table(name = "mall_promotion_full_reduction")
@Getter
@Setter
public class MallPromotionFullReduction extends BaseTenantEntity {

    /** 全部商品。 */
    public static final int SCOPE_ALL = 1;
    /** 指定分类。 */
    public static final int SCOPE_CATEGORY = 2;
    /** 指定商品。 */
    public static final int SCOPE_GOODS = 3;

    @Column(name = "activity_name", nullable = false, length = 64)
    private String activityName;

    /** 满减阶梯规则,JSON 数组。 */
    @Column(name = "reduction_rule", nullable = false, columnDefinition = "text")
    private String reductionRule;

    /** 1-全部商品 2-指定分类 3-指定商品。 */
    @Column(name = "scope_type", nullable = false)
    private Integer scopeType;

    @Column(name = "valid_start_time", nullable = false)
    private LocalDateTime validStartTime;

    @Column(name = "valid_end_time", nullable = false)
    private LocalDateTime validEndTime;

    @Column(name = "status", nullable = false)
    private Integer status;
}
