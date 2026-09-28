package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 会员等级定义(商城设计文档 3.11)。
 *
 * <p>等级由**近 N 个月滚动成长值**(字典 {@code growth_roll_months})与 {@code growthThreshold}
 * 比对得出,窗口滚出老值时**会降级**。判定取门槛降序里第一个够得着的等级。
 *
 * <p>{@code discountRate} 仍然**只存不用**:等级折扣与优惠券/满减的叠加顺序尚未确定,
 * 而"半实现的折扣"会真实地少收钱(开放项 2)。
 */
@Entity
@Table(name = "mall_member_level")
@Getter
@Setter
public class MallMemberLevel extends BaseTenantEntity {

    /**
     * 没匹配到任何等级定义时的展示名。
     *
     * <p>等级定义是**每租户自建**的(不预置种子):空库下所有客户都展示这个,
     * 想搞会员体系就在管理端建银卡/金卡。这样也避免了"平台替租户预置门槛"这件事。
     */
    public static final String DEFAULT_LEVEL_NAME = "普通会员";

    @Column(name = "level_name", nullable = false, length = 32)
    private String levelName;

    /** 等级顺序,数字越大等级越高。 */
    @Column(name = "level_sort", nullable = false)
    private Integer levelSort;

    /** 达到该成长值自动晋升到此等级。 */
    @Column(name = "growth_threshold", nullable = false)
    private Integer growthThreshold;

    /** 等级折扣率,如 0.95 表示 9.5 折;**只存不用**,不参与结算(见类注释)。 */
    @Column(name = "discount_rate", precision = 3, scale = 2)
    private BigDecimal discountRate;

    @Column(name = "status", nullable = false)
    private Integer status;
}
