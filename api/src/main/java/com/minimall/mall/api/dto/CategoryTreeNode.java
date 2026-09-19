package com.minimall.mall.api.dto;

import java.util.List;

/**
 * 商品分类树节点(两级)。
 */
public record CategoryTreeNode(
        Long id,
        Long parentId,
        String categoryName,
        String icon,
        Integer sortOrder,
        Integer status,
        List<CategoryTreeNode> children) {
}
