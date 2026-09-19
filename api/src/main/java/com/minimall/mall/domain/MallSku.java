package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * SKU(商城设计文档 3.2):多规格下价格与库存的实际载体。
 *
 * <p><b>库存的两个字段不能混用</b>:
 * <ul>
 *   <li>{@code stock}:实际库存(还没卖出去的实物数量)</li>
 *   <li>{@code lockedStock}:已下单未支付而锁定的数量</li>
 * </ul>
 * <b>可售库存 = {@code stock - lockedStock}</b>。列表页/详情页/下单校验都必须用它,
 * 直接展示或比较 {@code stock} 会卖出已经被别人锁定的货(超卖)。
 *
 * <p>支付成功后 {@code stock} 与 {@code lockedStock} **同时**减少(3.8):
 * 前者是货真的卖出去了,后者是锁定解除了。只减一个都会让可售库存算错。
 *
 * <p>{@code costPrice} 是成本价,只给商家后台看,**不对客户端暴露** —— 对外 View 里不要带它。
 */
@Entity
@Table(name = "mall_sku")
@Getter
@Setter
public class MallSku extends BaseTenantEntity {

    @Column(name = "goods_id", nullable = false)
    private Long goodsId;

    /** 商家自定义 SKU 编码,同租户内唯一(uk_tenant_sku_code)。 */
    @Column(name = "sku_code", nullable = false, length = 64)
    private String skuCode;

    /** 规格值组合的展示名,如"红色/XL"。冗余字段,避免每次查关联表拼接。 */
    @Column(name = "sku_name", nullable = false, length = 128)
    private String skuName;

    @Column(name = "sku_image", length = 255)
    private String skuImage;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** 成本价,仅商家后台可见,不对客户端暴露。 */
    @Column(name = "cost_price", precision = 10, scale = 2)
    private BigDecimal costPrice;

    @Column(name = "stock", nullable = false)
    private Integer stock;

    @Column(name = "locked_stock", nullable = false)
    private Integer lockedStock;

    /** 重量(kg),按重量计运费时使用(3.7)。 */
    @Column(name = "weight", precision = 10, scale = 3)
    private BigDecimal weight;

    /** 0-停售 1-正常。 */
    @Column(name = "status", nullable = false)
    private Integer status;

    /**
     * 可售库存 = 实际库存 - 锁定库存。
     *
     * <p>收敛成实体方法而不是各处手写减法:这个公式有两个使用方(展示、下单校验),
     * 写错一个方向(如 {@code lockedStock - stock})会让超卖判定完全失效。
     */
    public int availableStock() {
        int actual = stock == null ? 0 : stock;
        int locked = lockedStock == null ? 0 : lockedStock;
        return actual - locked;
    }
}
