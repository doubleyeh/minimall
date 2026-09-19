package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 商品主表(商城设计文档 3.2)。
 *
 * <p><b>{@code salePriceMin}/{@code salePriceMax}/{@code totalStock}/{@code saleCount} 是冗余汇总字段</b>:
 * 它们是为了列表页不被 N 次 SKU 求和拖慢而存在的。凡是 SKU 的价格/库存/销量发生变动,
 * 都必须在**同一个事务**内把它们同步更新 —— 两边不一致时,列表页显示的价格与详情页对不上,
 * 用户点进去会觉得"标价骗人"。更新入口统一收敛在 SKU 服务里,不要让每个调用点各自算一遍。
 *
 * <p>{@code totalStock} 统计的是 {@code stock}(实际库存),而**列表页展示的应是可售库存**:
 * 可售 = {@code stock - locked_stock}(见 3.2 与 {@link MallSku})。所以这里只做"汇总缓存",
 * 不能直接把它的值当作可售库存返回给端上。
 */
@Entity
@Table(name = "mall_goods")
@Getter
@Setter
public class MallGoods extends BaseTenantEntity {

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "goods_name", nullable = false, length = 128)
    private String goodsName;

    @Column(name = "goods_subtitle", length = 255)
    private String goodsSubtitle;

    /** 主图,列表页使用。 */
    @Column(name = "main_image", nullable = false, length = 255)
    private String mainImage;

    /** 详情富文本/HTML。用 text 列而不是 varchar:商品详情长度不可控。 */
    @Column(name = "detail_content", columnDefinition = "text")
    private String detailContent;

    @Column(name = "sale_price_min", nullable = false, precision = 10, scale = 2)
    private BigDecimal salePriceMin;

    @Column(name = "sale_price_max", nullable = false, precision = 10, scale = 2)
    private BigDecimal salePriceMax;

    @Column(name = "total_stock", nullable = false)
    private Integer totalStock;

    @Column(name = "sale_count", nullable = false)
    private Integer saleCount;

    /** 关联运费模板,为空表示包邮。 */
    @Column(name = "freight_template_id")
    private Long freightTemplateId;

    /** 0-下架 1-上架。下架**不做物理删除** SKU 与历史订单关联(3.2)。 */
    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
