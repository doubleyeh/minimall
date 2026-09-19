package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券定义(商城设计文档 3.10)。
 *
 * <p><b>{@code receivedCount} 是防超发的关键字段</b>:领取时用
 * {@code UPDATE ... SET received_count = received_count + 1 WHERE received_count < total_count}
 * 那种条件更新来占名额(受影响行数为 0 就是领完了),而不是"先查再判断再更新" ——
 * 后者在并发领取时必然超发。
 *
 * <p>优惠类型三选一,由 {@code couponType} 决定用 {@code discountAmount} 还是 {@code discountRate}。
 * 另一个字段留空是正常状态,不要在代码里给它们设默认值掩盖配置错误。
 */
@Entity
@Table(name = "mall_coupon")
@Getter
@Setter
public class MallCoupon extends BaseTenantEntity {

    /** 满减券:用 discountAmount。 */
    public static final int TYPE_FULL_REDUCTION = 1;
    /** 折扣券:用 discountRate。 */
    public static final int TYPE_DISCOUNT = 2;
    /** 无门槛现金券:用 discountAmount,minOrderAmount 通常为 0。 */
    public static final int TYPE_CASH = 3;

    @Column(name = "coupon_name", nullable = false, length = 64)
    private String couponName;

    /** 1-满减券 2-折扣券 3-无门槛现金券。 */
    @Column(name = "coupon_type", nullable = false)
    private Integer couponType;

    /** couponType = 1/3 时使用,减免金额。 */
    @Column(name = "discount_amount", precision = 10, scale = 2)
    private BigDecimal discountAmount;

    /** couponType = 2 时使用,如 0.9 表示 9 折。 */
    @Column(name = "discount_rate", precision = 3, scale = 2)
    private BigDecimal discountRate;

    /** 满多少可用,无门槛券填 0。 */
    @Column(name = "min_order_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal minOrderAmount;

    /** 发放总量。 */
    @Column(name = "total_count", nullable = false)
    private Integer totalCount;

    /** 已领取数量:冗余字段,用于条件更新防超发。 */
    @Column(name = "received_count", nullable = false)
    private Integer receivedCount;

    /** 每人限领数量。 */
    @Column(name = "per_customer_limit", nullable = false)
    private Integer perCustomerLimit;

    @Column(name = "valid_start_time", nullable = false)
    private LocalDateTime validStartTime;

    @Column(name = "valid_end_time", nullable = false)
    private LocalDateTime validEndTime;

    /** 0-已停用 1-进行中。 */
    @Column(name = "status", nullable = false)
    private Integer status;
}
