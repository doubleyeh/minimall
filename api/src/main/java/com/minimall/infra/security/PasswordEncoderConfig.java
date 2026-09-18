package com.minimall.infra.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码哈希(架构文档 7.1、7.1.2)。
 *
 * <p>只引 {@code spring-security-crypto} 这个纯工具包,不引 spring-boot-starter-security——
 * 本方案的认证鉴权走 Sa-Token,不需要 Spring Security 的过滤器链,引进来只会多一层互相打架的配置。
 *
 * <p>cost 固定 10:与建表脚本里种子数据(平台超管 admin123)用的成本一致。
 * 改这个值不影响老密码校验(BCrypt 的 hash 自带成本因子),只影响新写入的 hash。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
