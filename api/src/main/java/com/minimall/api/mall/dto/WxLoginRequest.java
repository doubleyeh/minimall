package com.minimall.api.mall.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 小程序登录请求(商城设计文档 3.1)。
 *
 * <p>只有 {@code code}:租户由请求头 {@code X-Tenant-Code} 指定(与后台的未登录公开接口同一套机制,4.9),
 * 不需要客户端传客户信息 —— 客户身份完全由微信凭证推导,端上传什么都不可信。
 */
public record WxLoginRequest(
        @NotBlank(message = "登录凭证不能为空")
        String code) {
}
