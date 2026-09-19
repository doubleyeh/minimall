package com.minimall.mall.service;

import com.minimall.api.mall.dto.ReviewCreateRequest;
import com.minimall.api.mall.dto.ReviewView;
import com.minimall.common.PageResult;

/**
 * 商品评价(商城设计文档 2)。
 *
 * <p>规则:只有**已完成**的订单明细可以评价,且一个明细只能评一次。
 * 这两条都由服务端强制,不依赖端上按钮是否置灰。
 */
public interface ReviewService {

    // ---------------------------------------------------------- 小程序端

    /** 提交评价。 */
    Long create(ReviewCreateRequest request);

    /** 某商品的评价列表(只含展示中的评价)。 */
    PageResult<ReviewView> listByGoods(Long goodsId, int pageNo, int pageSize);

    // ---------------------------------------------------------- 管理端

    PageResult<ReviewView> page(Long goodsId, Integer status, int pageNo, int pageSize);

    /** 商家回复。 */
    void reply(Long reviewId, String content);

    /** 显示/隐藏(违规内容下架,不物理删除,保留追溯能力)。 */
    void changeStatus(Long reviewId, Integer status);
}
