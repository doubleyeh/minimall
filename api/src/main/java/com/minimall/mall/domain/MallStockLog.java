package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 库存变动流水(商城设计文档 2)。
 *
 * <p><b>{@code stock}/{@code lockedStock} 的每一次变化都必须对应这里的一条记录</b> ——
 * 库存是钱,出现"账实不符"时唯一的排查依据就是这条流水。所以库存变动必须收敛到
 * 库存服务的少数几个方法里(下单锁定、超时释放、支付扣减、退款回库、手动调整),
 * 任何地方直接 {@code setStock(...)} 都会让流水与真实值脱节。
 */
@Entity
@Table(name = "mall_stock_log")
@Getter
@Setter
public class MallStockLog extends BaseTenantEntity {

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    /**
     * 1-下单锁定 2-超时释放 3-支付扣减实际库存 4-退款回库 5-管理端手动调整。
     *
     * <p>取值定义放在实体上(而不是散在 service):它是这张表的语义核心,
     * 后续加取值时应该在同一处补充,免得出现"6 是什么意思"没人知道。
     */
    @Column(name = "change_type", nullable = false)
    private Integer changeType;

    /** 变动数量,正数增加负数减少。 */
    @Column(name = "change_stock", nullable = false)
    private Integer changeStock;

    /** {@code lockedStock} 的变动量。 */
    @Column(name = "change_locked", nullable = false)
    private Integer changeLocked;

    /** 关联业务 ID(通常为订单 ID),手动调整时为空。 */
    @Column(name = "biz_id")
    private Long bizId;

    @Column(name = "remark", length = 255)
    private String remark;
}
