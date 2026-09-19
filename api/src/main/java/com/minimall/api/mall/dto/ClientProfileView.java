package com.minimall.api.mall.dto;

/**
 * 小程序端个人中心(商城设计文档 3.1)。
 *
 * <p>{@code openid} 刻意不返回:端上不需要它,返回它只会让它在日志与前端存储里多出现一次。
 * 订单角标用四个计数返回,而不是让端上分别调四次订单列表接口 —— 个人中心是高频页面,
 * 让它在首屏发五个请求毫无必要。
 */
public record ClientProfileView(
        Long customerId,
        String nickname,
        String avatarUrl,
        String phone,
        Integer gender,
        Integer points,
        Integer growthValue,
        OrderCounts orderCounts) {

    /** 个人中心的订单角标。 */
    public record OrderCounts(long pendingPay, long pendingShip, long pendingReceive, long finished) {
    }
}
