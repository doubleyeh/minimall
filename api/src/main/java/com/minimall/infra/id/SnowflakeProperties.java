package com.minimall.infra.id;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 雪花算法配置(架构文档 4.3、9.3)。
 *
 * <p>{@code workerId} 多实例部署时必须每个实例不同(由部署脚本/编排工具注入);
 * 未配置时退化为固定值,仅适用于单机开发。两个 ID 都用 5 bit,取值 0-31,
 * 超出范围直接启动失败——让"实例号配错"在启动时就暴露,而不是等 ID 撞车。
 */
@ConfigurationProperties(prefix = "minimall.snowflake")
public record SnowflakeProperties(Integer workerId, Integer datacenterId) {

    public static final int MAX_ID = 31;

    public SnowflakeProperties {
        workerId = workerId == null ? 1 : workerId;
        datacenterId = datacenterId == null ? 1 : datacenterId;
        if (workerId < 0 || workerId > MAX_ID) {
            throw new IllegalArgumentException("minimall.snowflake.worker-id 取值必须在 0-31,当前=" + workerId);
        }
        if (datacenterId < 0 || datacenterId > MAX_ID) {
            throw new IllegalArgumentException("minimall.snowflake.datacenter-id 取值必须在 0-31,当前=" + datacenterId);
        }
    }
}
