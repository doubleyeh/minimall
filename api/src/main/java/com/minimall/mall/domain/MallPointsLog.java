package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 积分变动流水(商城设计文档 2)。
 *
 * <p>与库存流水同理:积分是可兑换的资产,{@code mall_customer.points} 的每次变化
 * 都必须对应这里的一条记录。{@code balancePoints} 存"变动后余额"是为了对账方便 ——
 * 否则一旦怀疑某个客户的积分不对,只能按时间顺序把全部流水累加一遍(还很可能是错的,
 * 因为历史数据可能被迁移过)。
 */
@Entity
@Table(name = "mall_points_log")
@Getter
@Setter
public class MallPointsLog extends BaseTenantEntity {

    public static final int BIZ_GRANT = 1;
    public static final int BIZ_REDEEM = 2;
    public static final int BIZ_CLAWBACK = 3;
    public static final int BIZ_MANUAL = 4;
    public static final int BIZ_EXPIRE = 5;
    public static final int BIZ_RETURN = 6;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** 变动数量:正数增加、负数减少。 */
    @Column(name = "change_points", nullable = false)
    private Integer changePoints;

    /** 变动后余额,便于对账。 */
    @Column(name = "balance_points", nullable = false)
    private Integer balancePoints;

    /** 1-确认收货发放 2-抵现消耗 3-退款扣回 4-管理端手动调整 5-过期清零 6-订单关闭退回。 */
    @Column(name = "biz_type", nullable = false)
    private Integer bizType;

    /**
     * 关联业务 ID(订单 ID),管理端手动调整与过期清零时为空。
     *
     * <p>退款扣回时这里是订单 ID,售后单 ID 见 {@link #bizRefId} —— 两者都要有,
     * 扣回既要能按订单汇总(部分退款不超过发放值),又要能按售后单幂等。
     */
    @Column(name = "biz_id")
    private Long bizId;

    /** 关联售后单 ID,仅退款扣回有值。 */
    @Column(name = "biz_ref_id")
    private Long bizRefId;

    @Column(name = "remark", length = 255)
    private String remark;
}
