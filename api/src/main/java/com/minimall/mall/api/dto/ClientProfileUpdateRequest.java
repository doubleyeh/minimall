package com.minimall.mall.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新个人资料(小程序端)。
 *
 * <p>只允许改这三项:**积分、成长值、openid、phone 都不接受客户端提交** ——
 * 它们要么是资产(积分)、要么是身份凭证(openid),能改就等于能自己给自己加积分。
 */
public record ClientProfileUpdateRequest(
        @Size(max = 64, message = "昵称不能超过 64 个字符")
        String nickname,

        @Size(max = 255, message = "头像地址不能超过 255 个字符")
        String avatarUrl,

        Integer gender) {
}
