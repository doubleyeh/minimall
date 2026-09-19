package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 优惠券领取/使用记录(商城设计文档 3.10)。
 *
 * <p>领取与使用是两个独立动作:领取只产生一条 {@code status=1} 的记录,
 * 直到下单使用才关联 {@code orderId} 并置 {@code status=2}。
 *
 * <p>过期处理是"定时任务批量置 3 + 下单时再校验一次时间"的双保险(3.10):
 * 定时任务的执行时机不可靠,只靠它会出现"券已过期但还能用"的窗口。
 */
@Entity
@Table(name = "mall_coupon_record")
@Getter
@Setter
public class MallCouponRecord extends BaseTenantEntity {

    /** 未使用。 */
    public static final int STATUS_UNUSED = 1;
    /** 已使用。 */
    public static final int STATUS_USED = 2;
    /** 已过期。 */
    public static final int STATUS_EXPIRED = 3;

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** 1-未使用 2-已使用 3-已过期。 */
    @Column(name = "status", nullable = false)
    private Integer status;

    /** 使用时关联的订单,未使用为空。 */
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "receive_time", nullable = false)
    private LocalDateTime receiveTime;

    @Column(name = "use_time")
    private LocalDateTime useTime;
}
