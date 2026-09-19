package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 商品评价(商城设计文档 2)。
 *
 * <p>评价挂在 {@code order_item_id} 上而不是 {@code order_id}:一个订单可能买多件商品,
 * "一件只能评一次"这条规则只有落在明细行上才表达得出来(应用层校验)。
 */
@Entity
@Table(name = "mall_goods_review")
@Getter
@Setter
public class MallGoodsReview extends BaseTenantEntity {

    @Column(name = "goods_id", nullable = false)
    private Long goodsId;

    /** 关联订单明细行,一个明细行只能评价一次(应用层校验)。 */
    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** 1-5 星。 */
    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "content", length = 500)
    private String content;

    /** 晒图 URL 列表,JSON 数组字符串。 */
    @Column(name = "images", columnDefinition = "text")
    private String images;

    @Column(name = "is_anonymous", nullable = false)
    private Integer isAnonymous;

    @Column(name = "reply_content", length = 500)
    private String replyContent;

    @Column(name = "reply_time")
    private LocalDateTime replyTime;

    /** 0-隐藏(违规下架) 1-展示。用隐藏而不是删除,保留追溯能力。 */
    @Column(name = "status", nullable = false)
    private Integer status;
}
