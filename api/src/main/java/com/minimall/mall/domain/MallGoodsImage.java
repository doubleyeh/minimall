package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品轮播图(商城设计文档 2)。
 *
 * <p>与 {@code mall_goods.main_image} 分开维护:主图是列表页必须有的单张图,
 * 轮播图是详情页的可选多张图,两者生命周期不同(改轮播图不该动主图)。
 */
@Entity
@Table(name = "mall_goods_image")
@Getter
@Setter
public class MallGoodsImage extends BaseTenantEntity {

    @Column(name = "goods_id", nullable = false)
    private Long goodsId;

    @Column(name = "image_url", nullable = false, length = 255)
    private String imageUrl;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
