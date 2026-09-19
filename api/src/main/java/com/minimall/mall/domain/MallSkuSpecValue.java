package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * SKU 与规格值的组合关系(商城设计文档 2):一个 SKU 在每组规格下各取一个值
 * (如"颜色=红色、尺码=XL")。
 *
 * <p>"同一 SKU 在同组规格下只能有一个值"由库上的唯一键 {@code uk_sku_spec_value} 保证 ——
 * 这条约束如果用应用层校验实现,一旦并发写入就会漏(两个请求同时通过校验)。
 */
@Entity
@Table(name = "mall_sku_spec_value")
@Getter
@Setter
public class MallSkuSpecValue extends BaseTenantEntity {

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "spec_value_id", nullable = false)
    private Long specValueId;
}
