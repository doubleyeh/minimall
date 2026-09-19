package com.minimall.mall.service;

import com.minimall.mall.api.dto.CategoryTreeNode;
import com.minimall.mall.api.dto.ClientGoodsDetailView;
import com.minimall.mall.api.dto.ClientGoodsView;
import com.minimall.common.PageResult;

import java.util.List;

/**
 * 小程序端商品浏览(商城设计文档 3.2)。
 *
 * <p>只暴露**已上架**的商品:{@code status = 1}。下架商品对端上不可见(直接 404),
 * 而不是"显示但不可买" —— 后者会让用户以为还能买,点进去才发现没有购买入口。
 */
public interface ClientCatalogService {

    /** 在售分类树(只含启用分类)。 */
    List<CategoryTreeNode> categories();

    PageResult<ClientGoodsView> goods(Long categoryId, String keyword, int pageNo, int pageSize);

    /** 商品详情。下架商品对端上等同于不存在(抛 404 语义)。 */
    ClientGoodsDetailView detail(Long goodsId);
}
