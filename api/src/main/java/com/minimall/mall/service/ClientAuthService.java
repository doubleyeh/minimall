package com.minimall.mall.service;

import com.minimall.mall.api.dto.ClientLoginResponse;
import com.minimall.mall.api.dto.WxLoginRequest;

/**
 * 小程序客户登录(商城设计文档 3.1)。
 *
 * <p>处理顺序固定为:换 openid → 按 (租户, openid) 找客户 → 没有就建 → 签发令牌 → 记登录时间。
 * 其中"找客户"必须显式带上 {@code tenantId}:同一个微信号在不同租户的小程序里是两个不同的 openid,
 * 即使 openid 相同也不能跨租户复用客户记录 —— 那等于把一个租户的客户变成了另一个租户的客户。
 */
public interface ClientAuthService {

    /**
     * 微信小程序登录。
     *
     * @param tenantCode 请求头 {@code X-Tenant-Code} 带来的租户编码;为空时无法定位租户
     */
    ClientLoginResponse wxLogin(String tenantCode, WxLoginRequest request);
}
