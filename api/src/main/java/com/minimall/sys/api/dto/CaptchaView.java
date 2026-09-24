package com.minimall.sys.api.dto;

/**
 * 图形验证码(架构文档 7.1.1 的登录前置)。
 *
 * @param captchaId 验证码标识,登录时原样带回;服务端按它取答案,- 用过即失效
 * @param image     可直接放进 {@code <img src>} 的 data URL(PNG)
 */
public record CaptchaView(
        String captchaId,
        String image
) {
}
