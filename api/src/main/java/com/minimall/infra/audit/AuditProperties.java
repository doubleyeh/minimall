package com.minimall.infra.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审计日志的归档策略(架构文档 7.2)。
 *
 * @param archiveAfterDays 超过这个天数的日志先归档成文件、再从库里删除;0 表示不归档
 */
@ConfigurationProperties(prefix = "minimall.audit")
public record AuditProperties(Integer archiveAfterDays) {

    /**
     * 默认 180 天。
     *
     * <p>默认开着一个"会删数据"的策略,是因为它**删之前一定先落一份文件**:归档写失败就不删库里任何东西
     * (见 {@code OperLogArchiver})。而这个策略关着的代价是实打实的 —— 操作日志是每写一次请求就多一行的表,
     * 只增不减迟早会把库压垮。
     */
    private static final int DEFAULT_ARCHIVE_AFTER_DAYS = 180;

    public AuditProperties {
        archiveAfterDays = archiveAfterDays == null ? DEFAULT_ARCHIVE_AFTER_DAYS : archiveAfterDays;
        if (archiveAfterDays < 0) {
            throw new IllegalArgumentException("minimall.audit.archive-after-days 不能为负数");
        }
    }
}
