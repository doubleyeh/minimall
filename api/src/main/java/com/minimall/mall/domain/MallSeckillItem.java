package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 秒杀活动商品项(商城设计文档 3.12)。
 *
 * <p><b>{@code seckillStock} 是配额,{@code soldCount} 是已占用</b>(下单即算,取消回退)。
 * 两者不能在 Java 里"先读再改":并发抢最后一件时两个请求都会读到可卖,然后各扣一次。
 * 占用必须用条件更新 {@code sold_count + q <= seckill_stock},受影响行数为 0 就是抢光了。
 *
 * <p>{@code soldCount} 不是"已支付件数",而是"已占用的配额";支付与退款都不改它,
 * 只有下单与取消会改。这样配额收支只有两个入口,对账简单。
 */
@Entity
@Table(name = "mall_seckill_item")
@Getter
@Setter
public class MallSeckillItem extends BaseTenantEntity {

    @Column(name = "activity_id", nullable = false)
    private Long activityId;

    @Column(name = "goods_id", nullable = false)
    private Long goodsId;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "seckill_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal seckillPrice;

    /** 活动配额,发布时从 SKU 可售库存冻结。 */
    @Column(name = "seckill_stock", nullable = false)
    private Integer seckillStock;

    /** 已占用配额(下单即算,取消回退)。 */
    @Column(name = "sold_count", nullable = false)
    private Integer soldCount;

    @Column(name = "per_customer_limit", nullable = false)
    private Integer perCustomerLimit;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** 剩余可抢配额。只用于展示;并发判断必须在数据库的条件更新里。 */
    public int remaining() {
        int quota = seckillStock == null ? 0 : seckillStock;
        int sold = soldCount == null ? 0 : soldCount;
        return quota - sold;
    }
}
