package com.minimall.mall.api.dto;

import java.time.LocalDateTime;

/**
 * 积分流水(小程序"积分明细"与管理端客户详情共用)。
 *
 * <p>{@code bizTypeText} 在服务端拼好:积分变动的原因对用户是文案而不是枚举值,
 * 而这段映射在小程序与管理端都要用,放两边各写一份必然走偏。
 */
public record PointsLogView(
        Long id,
        Integer changePoints,
        /** 变动后余额,便于对账。 */
        Integer balancePoints,
        Integer bizType,
        String bizTypeText,
        String remark,
        LocalDateTime createTime) {

    public static String text(Integer bizType) {
        if (bizType == null) {
            return "积分变动";
        }
        return switch (bizType) {
            case 1 -> "确认收货获得";
            case 2 -> "下单抵扣";
            case 3 -> "退款扣回";
            case 4 -> "后台调整";
            case 5 -> "过期清零";
            case 6 -> "订单关闭退回";
            default -> "积分变动";
        };
    }
}
