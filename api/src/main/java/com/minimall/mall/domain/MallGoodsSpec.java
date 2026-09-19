package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品规格名(商城设计文档 2、3.2),如"颜色""尺码"。一个商品可有多组规格。
 *
 * <p>规格名 → 规格值 → SKU 是三层:前者描述"有哪些维度",
 * {@link MallSku} 才是价格与库存的实际载体。
 */
@Entity
@Table(name = "mall_goods_spec")
@Getter
@Setter
public class MallGoodsSpec extends BaseTenantEntity {

    @Column(name = "goods_id", nullable = false)
    private Long goodsId;

    @Column(name = "spec_name", nullable = false, length = 32)
    private String specName;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
