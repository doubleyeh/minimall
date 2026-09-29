package com.minimall.mall.service.support;

import com.minimall.mall.api.dto.AdminOrderView;
import com.minimall.mall.domain.MallOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理端订单导出的 CSV 形状。
 *
 * <p><b>一行一笔订单</b>,不摊开明细:导出的用途是对账与交接,一笔一行才好筛选与求和。
 *
 * <p>金额列保持原样(两位小数的纯数字),不加"¥"也不加千分位 —— 加了 Excel 会当成文本,
 * 拿到文件的人没法直接求和。
 */
public final class AdminOrderCsv {

    public static final List<String> HEADERS = List.of(
            "订单号", "状态", "买家ID", "商品金额", "运费", "满减优惠", "优惠券", "实付金额",
            "收货人", "收货电话", "收货地址", "物流公司", "物流单号",
            "下单时间", "支付时间", "发货时间", "买家留言");

    private AdminOrderCsv() {
    }

    public static List<String> cells(AdminOrderView view) {
        return List.of(
                view.orderNo(),
                statusText(view.status()),
                String.valueOf(view.customerId()),
                plain(view.goodsAmount()),
                plain(view.freightAmount()),
                plain(view.promotionDiscountAmount()),
                plain(view.couponDiscountAmount()),
                plain(view.payAmount()),
                blankIfNull(view.receiverName()),
                blankIfNull(view.receiverPhone()),
                blankIfNull(view.receiverAddress()),
                blankIfNull(view.logisticsCompany()),
                blankIfNull(view.logisticsNo()),
                String.valueOf(view.createTime()),
                blankIfNull(view.payTime()),
                blankIfNull(view.shipTime()),
                blankIfNull(view.remark()));
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? "" : amount.toPlainString();
    }

    private static String blankIfNull(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 状态文案与前端列表页一致:导出文件脱离界面,只给数字没人看得懂。 */
    private static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case MallOrder.STATUS_PENDING_PAY -> "待支付";
            case MallOrder.STATUS_PENDING_SHIP -> "待发货";
            case MallOrder.STATUS_PENDING_RECEIVE -> "待收货";
            case MallOrder.STATUS_FINISHED -> "已完成";
            case MallOrder.STATUS_CANCELLED -> "已取消";
            case MallOrder.STATUS_AFTER_SALE -> "售后中";
            default -> "未知";
        };
    }
}
