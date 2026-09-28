package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 成长值流水(商城设计文档 3.4)。
 *
 * <p>当前成长值 = 近 N 个月(字典 {@code growth_roll_months})内所有记录的
 * {@code changeGrowth} 之和。因为要按窗口滚动扣掉老值,必须有带时间戳的账本 ——
 * 只靠 {@code mall_customer.growth_value} 一个计数器算不出"最近涨了多少"。
 *
 * <p>与积分分账:抵现消耗与过期清零都不写这里(它们不改变成长值)。
 */
@Entity
@Table(name = "mall_growth_log")
@Getter
@Setter
public class MallGrowthLog extends BaseTenantEntity {

    /** 1-确认收货发放 3-退款扣回 4-管理端手动调整。 */
    public static final int BIZ_GRANT = 1;
    public static final int BIZ_CLAWBACK = 3;
    public static final int BIZ_MANUAL = 4;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "change_growth", nullable = false)
    private Integer changeGrowth;

    @Column(name = "biz_type", nullable = false)
    private Integer bizType;

    /** 关联订单 ID,管理端手动调整时为空。 */
    @Column(name = "biz_id")
    private Long bizId;

    /** 关联售后单 ID,退款扣回靠它做幂等。 */
    @Column(name = "biz_ref_id")
    private Long bizRefId;

    @Column(name = "remark", length = 255)
    private String remark;
}
