package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 订单抵现占用的批次明细(商城设计文档 3.4)。
 *
 * <p>订单关闭时按这张表把积分退回**原批次**,保持原有效期 —— 新建批次的话,
 * 反复下单再取消就能让积分永久续期。退回后删行,所以重复取消不会二次退回。
 */
@Entity
@Table(name = "mall_points_use")
@Getter
@Setter
public class MallPointsUse extends BaseTenantEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    /** 本订单从该批次消耗的积分数。 */
    @Column(name = "points", nullable = false)
    private Integer points;
}
