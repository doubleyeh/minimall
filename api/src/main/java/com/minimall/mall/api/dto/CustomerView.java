package com.minimall.mall.api.dto;

import java.time.LocalDateTime;

/**
 * 管理端客户列表行。
 *
 * <p>{@code memberLevelName} 是解析后的展示名(而不是 levelId):运营看的是"金卡会员",
 * 给他一个雪花 ID 没有任何意义。
 */
public record CustomerView(
        Long id,
        String nickname,
        String phone,
        String memberLevelName,
        Integer points,
        Integer growthValue,
        LocalDateTime registerTime) {
}
