package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 规格值(商城设计文档 2),如"红色""XL",挂在某个 {@link MallGoodsSpec} 下。
 */
@Entity
@Table(name = "mall_goods_spec_value")
@Getter
@Setter
public class MallGoodsSpecValue extends BaseTenantEntity {

    @Column(name = "spec_id", nullable = false)
    private Long specId;

    @Column(name = "spec_value", nullable = false, length = 32)
    private String specValue;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;
}
