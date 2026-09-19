package com.minimall.mall.service;

import com.minimall.mall.api.dto.CategorySaveRequest;
import com.minimall.mall.api.dto.CategoryTreeNode;

import java.util.List;

/**
 * 商品分类维护(商城设计文档 3.2)。
 *
 * <p>分类只支持两级:{@code parentId} 指向一级分类,一级分类的 {@code parentId} 为 0。
 * 服务端强制这条规则(不允许把二级分类再往下挂),因为客户端导航就是按两层设计的,
 * 多出来的层级在端上无处展示。
 */
public interface GoodsCategoryService {

    /** 分类树。{@code status} 为空返回全部。 */
    List<CategoryTreeNode> tree(Integer status);

    Long create(CategorySaveRequest request);

    void update(Long categoryId, CategorySaveRequest request);

    /**
     * 删除分类:{@code 5.6} 的口径 —— 有子分类或已被商品引用时拒绝删除,不做静默裁剪。
     */
    void delete(Long categoryId);
}
