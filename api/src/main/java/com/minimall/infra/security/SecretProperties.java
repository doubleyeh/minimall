package com.minimall.infra.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 敏感配置的信封加密主密钥(架构文档 7.2)。
 *
 * <p>只从环境变量取,不落库不外泄:库里与 Redis 里存的都是密文,明文只在进程内存中出现。
 */
@ConfigurationProperties(prefix = "minimall.secret")
public record SecretProperties(String masterKey) {
}
