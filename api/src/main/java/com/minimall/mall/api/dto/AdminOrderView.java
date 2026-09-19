package com.minimall.mall.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家管理端订单视图。
 *
 * <p>比端上视图多了 {@code customerId}(客服核对身份时要看)与 {@code goodsName} 汇总(列表页展示"买了什么"),
 * 少了买家侧不需要的字段。**不含成本价**:SkuView 里那些内部字段在这里同样不返回。
 */
public record AdminOrderView(
        Long id,
        String orderNo,
        Long customerId,
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
        /** 详情接口才返回明细;列表接口传 null(列表页不需要,省一次查询) */
        List<ClientOrderView.Item> items) {
}
