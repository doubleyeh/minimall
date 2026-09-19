package com.minimall.api.mall.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 小程序端订单(列表与详情共用一个结构)。
 *
 * <p>列表页与详情页共用刻意为之:端上从列表点进详情时可以直接用列表数据渲染首屏,
 * 再异步补全详情,不必等一次白屏。代价是列表接口要多带明细,
 * 所以列表按分页限制条数(见 {@code OrderService#list})。
 */
public record ClientOrderView(
        Long id,
        String orderNo,
        Integer status,
        BigDecimal goodsAmount,
        BigDecimal freightAmount,
        BigDecimal promotionDiscountAmount,
        BigDecimal couponDiscountAmount,
        BigDecimal payAmount,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        String remark,
        String logisticsCompany,
        String logisticsNo,
        Integer closeReason,
        LocalDateTime createTime,
        LocalDateTime payTime,
        LocalDateTime shipTime,
        LocalDateTime receiveTime,
        LocalDateTime finishTime,
        List<Item> items) {

    /** 订单明细(下单时的快照,商品改名换图都不影响历史订单展示)。 */
    public record Item(
            Long id,
            Long skuId,
            Long goodsId,
            String goodsName,
            String skuName,
            String goodsImage,
            BigDecimal price,
            Integer quantity,
            BigDecimal totalAmount,
            Integer afterSaleStatus) {
    }
}
