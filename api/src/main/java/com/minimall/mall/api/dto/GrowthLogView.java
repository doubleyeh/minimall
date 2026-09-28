package com.minimall.mall.api.dto;

import java.time.LocalDateTime;

/**
 * 成长值流水(管理端客户详情)。
 *
 * <p>与积分流水一样,{@code bizTypeText} 在服务端拼好 —— 端上各写一份映射必然走偏。
 */
public record GrowthLogView(
        Long id,
        Integer changeGrowth,
        Integer bizType,
        String bizTypeText,
        String remark,
        LocalDateTime createTime) {

    public static String text(Integer bizType) {
        if (bizType == null) {
            return "成长值变动";
        }
        return switch (bizType) {
            case 1 -> "确认收货获得";
            case 3 -> "退款扣回";
            case 4 -> "后台调整";
            default -> "成长值变动";
        };
    }
}
