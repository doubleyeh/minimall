package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 积分批次(商城设计文档 3.4):一笔发放对应一个批次,分批过期。
 *
 * <p>可用积分 = 该客户所有"剩余大于 0 且未过期"批次的 {@code remainPoints} 之和。
 * 消耗按 {@code expireTime} 升序、同过期时间按 ID 升序,先到期的先用。
 */
@Entity
@Table(name = "mall_points_batch")
@Getter
@Setter
public class MallPointsBatch extends BaseTenantEntity {

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** 来源发放流水 ID,便于从批次追回是哪一次发放产生的。 */
    @Column(name = "source_log_id")
    private Long sourceLogId;

    /** 发放总量,落库后不再变(区别于随消耗减少的 remainPoints)。 */
    @Column(name = "total_points", nullable = false)
    private Integer totalPoints;

    @Column(name = "remain_points", nullable = false)
    private Integer remainPoints;

    @Column(name = "expire_time", nullable = false)
    private LocalDateTime expireTime;
}
