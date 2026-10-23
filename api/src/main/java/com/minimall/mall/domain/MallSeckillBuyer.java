package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 秒杀限购账本(商城设计文档 3.12):一人一行。
 *
 * <p><b>限购的真相在这里,不在 Redis。</b>Redis 的计数只负责让超限请求不进数据库;
 * Redis 丢 key、被清空、重建不准时,以这张表的 {@code boughtCount} 为准。
 *
 * <p>行锁粒度是"这个人 × 这个商品",不同用户抢同一件商品互不阻塞 —— 如果改成锁商品行,
 * 全场只有一个用户在推进,吞吐直接被行锁卡死。
 */
@Entity
@Table(name = "mall_seckill_buyer")
@Getter
@Setter
public class MallSeckillBuyer extends BaseTenantEntity {

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** 已购件数,取消订单时回退。 */
    @Column(name = "bought_count", nullable = false)
    private Integer boughtCount;
}
