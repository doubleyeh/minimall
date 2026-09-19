package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 订单明细(商城设计文档 3.2、3.3)。
 *
 * <p><b>商品与 SKU 的信息全部落快照</b>(名称、图、单价):商品可以改名、换图、下架甚至改价,
 * 但历史订单必须永远显示"当时买的是什么、多少钱"。所以这张表**不实时关联**商品主表 ——
 * 需要展示时直接用快照字段,连 join 都不需要。
 *
 * <p>{@code goodsId}/{@code skuId} 仍然保留:它们用于售后、评价、"再次购买"这类
 * 需要回到实体的场景,只是**不能用来取展示信息**。
 *
 * <p>{@code afterSaleStatus} 是冗余字段:详情页要判断"这行还能不能申请售后",
 * 每次都去 {@code mall_after_sale} 查一遍会让订单详情页产生 N+1 次查询。
 */
@Entity
@Table(name = "mall_order_item")
@Getter
@Setter
public class MallOrderItem extends BaseTenantEntity {

    /** 无售后。 */
    public static final int AFTER_SALE_NONE = 0;
    /** 售后处理中。 */
    public static final int AFTER_SALE_PROCESSING = 1;
    /** 售后已完成。 */
    public static final int AFTER_SALE_DONE = 2;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    /** 冗余字段,售后/评价按 goods_id 查询更方便。 */
    @Column(name = "goods_id", nullable = false)
    private Long goodsId;

    @Column(name = "goods_name", nullable = false, length = 128)
    private String goodsName;

    @Column(name = "sku_name", nullable = false, length = 128)
    private String skuName;

    @Column(name = "goods_image", nullable = false, length = 255)
    private String goodsImage;

    /** 下单时单价快照。 */
    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** {@code price * quantity}。落库而不是每次乘:退款金额按它计算,必须与下单时一致。 */
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    /** 0-无售后 1-售后处理中 2-售后已完成。 */
    @Column(name = "after_sale_status", nullable = false)
    private Integer afterSaleStatus;
}
