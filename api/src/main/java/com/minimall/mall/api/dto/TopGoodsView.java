package com.minimall.mall.api.dto;

import java.math.BigDecimal;

/**
 * 商品销售排行行(管理端)。
 *
 * <p>{@code goodsName} 取订单明细里的**快照名**:商品改名后历史订单显示的还是下单时的名字,
 * 这正是快照字段存在的意义。{@code quantity}/{@code amount} 是下单口径,已退款的那部分没有扣掉。
 */
public record TopGoodsView(
        Long goodsId,
        String goodsName,
        long quantity,
        BigDecimal amount) {
}
