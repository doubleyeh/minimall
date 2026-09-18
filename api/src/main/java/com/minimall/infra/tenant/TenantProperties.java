package com.minimall.infra.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 租户相关配置(架构文档 4.9、4.11)。
 *
 * @param publicPaths                白名单:允许在没有 tenantId 的情况下执行的路径。
 *                                   **必须显式维护**,不做"路径里含 auth 就放行"这类模糊匹配
 * @param tenantStatusCacheSeconds   租户状态缓存 TTL(秒),默认 60(4.11 的兜底收敛时间)
 */
@ConfigurationProperties(prefix = "minimall.tenant")
public record TenantProperties(List<String> publicPaths, Integer tenantStatusCacheSeconds) {

    public TenantProperties {
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
        tenantStatusCacheSeconds = tenantStatusCacheSeconds == null ? 60 : tenantStatusCacheSeconds;
    }
}
