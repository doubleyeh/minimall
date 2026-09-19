package com.minimall.mall.service;

import com.minimall.api.mall.dto.GoodsDetailView;
import com.minimall.api.mall.dto.GoodsSaveRequest;
import com.minimall.api.mall.dto.GoodsView;
import com.minimall.common.PageResult;

/**
 * 商品与 SKU 维护(商城设计文档 3.2)。
 *
 * <p><b>本服务是商品汇总字段的唯一写入口</b>:{@code salePriceMin}/{@code salePriceMax}/
 * {@code totalStock} 必须与 SKU 保持同事务一致。任何绕过这里直接改 SKU 价格的代码,
 * 都会让列表页显示的价格与详情页对不上 —— 所以库存与价格的变动都要经过本服务(或调用它的私有重算)。
 */
public interface GoodsService {

    PageResult<GoodsView> page(String goodsName, Long categoryId, Integer status, int pageNo, int pageSize);

    GoodsDetailView detail(Long goodsId);

    Long create(GoodsSaveRequest request);

    void update(Long goodsId, GoodsSaveRequest request);

    /**
     * 上架/下架。下架**不删除 SKU 与历史订单关联**(3.2):
     * 已下单的订单明细走快照展示,不受商品下架影响。
     */
    void changeStatus(Long goodsId, Integer status);

    /**
     * 删除商品。仅在**没有任何订单明细引用**时允许 —— 有历史的商品只能下架。
     * 删除会连带清理轮播图、规格、SKU 及其关联(这些数据没有独立业务含义)。
     */
    void delete(Long goodsId);
}
