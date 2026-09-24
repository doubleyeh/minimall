package com.minimall.mall.infra.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 客户端令牌与微信小程序的配置(商城设计文档 3.1)。
 *
 * @param jwtSecret JWT 签名密钥。**必须在部署环境用配置覆盖**:同一个密钥泄露等于任何人都能
 *                  伪造任意客户的令牌(包括别人的 customerId)
 * @param ttlDays   令牌有效期(天)。默认 30 天:小程序场景不存在"主动登出",过期后前端静默重登
 * @param wxMock    是否使用模拟的 code2Session(仅用于本地/自动化测试)。
 *                  **生产必须为 false** —— 打开它等于任何人写个 code 就能登录成任意客户
 *
 * <p>小程序的 appId / appSecret 不在这里:它们是**按租户**的(每个租户可以是自己的小程序),
 * 放在 {@code sys_wx_pay_config} 里,由 {@code WxPayConfigProvider} 提供。
 */
@ConfigurationProperties(prefix = "minimall.mall.auth")
public record ClientTokenProperties(
        String jwtSecret,
        Integer ttlDays,
        Boolean wxMock) {

    public int ttlDaysOrDefault() {
        return ttlDays == null || ttlDays <= 0 ? 30 : ttlDays;
    }

    public boolean wxMockEnabled() {
        return Boolean.TRUE.equals(wxMock);
    }
}
