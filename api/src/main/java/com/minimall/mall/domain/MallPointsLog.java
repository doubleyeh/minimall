package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
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

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** 变动数量:正数增加、负数减少。 */
    @Column(name = "change_points", nullable = false)
    private Integer changePoints;

    /** 变动后余额,便于对账。 */
    @Column(name = "balance_points", nullable = false)
    private Integer balancePoints;

    /** 1-下单获得 2-兑换消耗 3-退款扣回 4-管理端手动调整 5-过期清零。 */
    @Column(name = "biz_type", nullable = false)
    private Integer bizType;

    /** 关联业务 ID(如订单 ID),管理端手动调整时为空。 */
    @Column(name = "biz_id")
    private Long bizId;

    @Column(name = "remark", length = 255)
    private String remark;
}
