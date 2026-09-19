package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品分类(商城设计文档 2、3.2)。
 *
 * <p>只做两级(一级/二级),用 {@code parent_id} 表达,顶层为 0。
 * 刻意不引入 {@code sys_dept} 那种 {@code ancestors} 祖级链:分类的层级固定为两层,
 * 没有"本分类及以下"这类递归查询需求,加了祖级链只会多一份需要在移动时维护的冗余字段。
 */
@Entity
@Table(name = "mall_goods_category")
@Getter
@Setter
public class MallGoodsCategory extends BaseTenantEntity {

    /** 上级分类 ID,顶层为 0。 */
    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(name = "category_name", nullable = false, length = 64)
    private String categoryName;

    @Column(name = "icon", length = 255)
    private String icon;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** 0-停用 1-正常。停用分类不影响已上架商品的历史归属。 */
    @Column(name = "status", nullable = false)
    private Integer status;
}
