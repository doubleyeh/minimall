package com.minimall.infra.config;

import com.minimall.infra.audit.AuditContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.util.Optional;

/**
 * 审计字段自动填充(架构文档 4.4)。
 *
 * <p>时间字段由 Spring Data JPA Auditing 的 {@code @CreatedDate}/{@code @LastModifiedDate} 自动填;
 * 操作人字段的取值来源是 {@link AuditContext},**不是**直接读 Sa-Token 会话——
 * 后者在异步落库(操作日志)和异步任务里取不到,而 {@code AuditContext} 由 HTTP 入口填充、
 * 异步场景显式传入,同一套逻辑覆盖两种路径(见 4.12)。
 *
 * <p>注意 Spring Boot 不会自动开启 JPA Auditing,必须显式 {@code @EnableJpaAuditing},
 * 否则 {@code @CreatedDate} 这类注解会被静默忽略(字段为 null,而数据库有 NOT NULL 默认值兜底,
 * 表面上"看起来正常",其实审计字段全靠数据库默认值,操作人永远是 null)。
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<Long> auditorAware() {
        return () -> Optional.ofNullable(AuditContext.currentUserId());
    }
}
