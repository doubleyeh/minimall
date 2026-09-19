package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 满减活动适用范围(商城设计文档 3.10、开放项 1)。
 *
 * <p>{@code scopeId} 的含义随所属活动的 {@code scopeType} 变化(分类 ID 或商品 ID)。
 * 本期**只支持"包含"逻辑,不支持排除**(如"全部商品参与,除 XX 分类外")——
 * 排除逻辑会让"这个商品参不参与"变成需要求差集的判断,错误率明显更高,等业务明确需要再做。
 *
 * <p>{@code scopeType = 1}(全部商品)时本表不记录。
 */
@Entity
@Table(name = "mall_promotion_full_reduction_scope")
@Getter
@Setter
public class MallPromotionFullReductionScope extends BaseTenantEntity {

    @Column(name = "activity_id", nullable = false)
    private Long activityId;

    /** 按所属活动的 scopeType,存 category_id 或 goods_id。 */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;
}
