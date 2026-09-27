package com.minimall.infra.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 脱敏规则的配置(架构文档 7.2)。
 *
 * <p>字段名单放在配置里而不是写死在代码里:线上发现漏了某个字段(比如新加的 xxxKey),
 * 加一行配置重启即可,不用改代码发版。
 *
 * @param sensitiveFields 需要整体掩码的字段名(密码、令牌、密钥、证件号等)
 * @param phoneFields     需要按"保留前三后四"掩码的字段名
 */
@ConfigurationProperties(prefix = "minimall.masking")
public record MaskingProperties(List<String> sensitiveFields, List<String> phoneFields) {

    /** 默认名单与拆分前的硬编码列表一致,拆配置时不该顺带改变行为。 */
    private static final List<String> DEFAULT_SENSITIVE_FIELDS = List.of(
            "password", "oldPassword", "newPassword", "confirmPassword", "pwd",
            "token", "accessToken", "refreshToken",
            "secret", "appSecret", "privateKey", "idCard", "idNumber", "bankCard");

    private static final List<String> DEFAULT_PHONE_FIELDS = List.of(
            "phone", "mobile", "mobilePhone", "telephone");

    public MaskingProperties {
        sensitiveFields = (sensitiveFields == null || sensitiveFields.isEmpty())
                ? DEFAULT_SENSITIVE_FIELDS : List.copyOf(sensitiveFields);
        phoneFields = (phoneFields == null || phoneFields.isEmpty())
                ? DEFAULT_PHONE_FIELDS : List.copyOf(phoneFields);
    }
}
