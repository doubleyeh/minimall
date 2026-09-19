package com.minimall.mall.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 售后单(端上与管理端共用)。
 *
 * <p>带上 {@code orderItem} 的快照信息(商品名/规格/图/单价):售后页面必须能显示"退的是哪件商品",
 * 而售后单本身只存了 {@code orderItemId}。
 */
public record AfterSaleView(
        Long id,
        String afterSaleNo,
        Long orderId,
        String orderNo,
        Long orderItemId,
        Long customerId,
        Integer afterSaleType,
        Integer status,
        String statusText,
        String applyReason,
        String applyDesc,
        BigDecimal refundAmount,
        String rejectReason,
        String returnLogisticsCompany,
        String returnLogisticsNo,
        String reshipLogisticsCompany,
        String reshipLogisticsNo,
        String arbitrationRemark,
        LocalDateTime createTime,
        LocalDateTime finishTime,
        List<String> images,
        /** 售后单对应的商品(来自订单明细快照) */
        Item item,
        List<LogItem> logs) {

    /** 关联的订单明细快照。 */
    public record Item(String goodsName, String skuName, String goodsImage, BigDecimal price, Integer quantity) {
    }

    /** 状态流转记录(3.9 要求商家下调退款金额等操作留痕)。 */
    public record LogItem(
            Integer fromStatus,
            Integer toStatus,
            Integer operatorType,
            String remark,
            LocalDateTime createTime) {
    }
}
