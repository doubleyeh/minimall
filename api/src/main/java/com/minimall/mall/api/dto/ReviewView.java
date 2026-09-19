package com.minimall.mall.api.dto;

import java.time.LocalDateTime;

/**
 * 商品评价(端上展示与管理端管理共用)。
 *
 * <p>{@code customerNickname} 在匿名评价时由服务端置为"匿名用户" ——
 * 判断放在服务端而不是端上:端上拿到真实昵称再"选择不显示",等于已经泄露了。
 */
public record ReviewView(
        Long id,
        Long goodsId,
        String goodsName,
        String customerNickname,
        Integer rating,
        String content,
        String images,
        Integer isAnonymous,
        String replyContent,
        LocalDateTime replyTime,
        Integer status,
        LocalDateTime createTime) {
}
