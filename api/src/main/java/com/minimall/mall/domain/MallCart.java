package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 购物车(商城设计文档 2)。
 *
 * <p>唯一键 {@code (tenant_id, customer_id, sku_id)}:同一客户同一 SKU 只保留一行,
 * 重复加购是"数量累加"而不是插新行。靠数据库唯一键而不是先查后插 ——
 * 用户连点两次加购时,两个请求会同时通过"查不到"的判断,然后插出两行。
 *
 * <p>购物车不存价格快照:结算时以 SKU 当前价格为准(与订单明细的快照语义相反)。
 * 这是刻意的 —— 购物车是"想买什么",订单才是"以什么价格成交"。
 */
@Entity
@Table(name = "mall_cart")
@Getter
@Setter
public class MallCart extends BaseTenantEntity {

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** 结算页是否勾选,1-选中。 */
    @Column(name = "selected", nullable = false)
    private Integer selected;
}
