package com.minimall.infra.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 租户相关配置(架构文档 4.9、4.11)。
 *
 * @param publicPaths                白名单:允许在没有 tenantId 的情况下执行的路径。
 *                                   **必须显式维护**,不做"路径里含 auth 就放行"这类模糊匹配
 * @param tenantStatusCacheSeconds   租户状态缓存 TTL(秒),默认 60(4.11 的兜底收敛时间)
 * @param purgeAfterDays             注销到物理清理的保留期(天),默认 90
 */
@ConfigurationProperties(prefix = "minimall.tenant")
public record TenantProperties(List<String> publicPaths, Integer tenantStatusCacheSeconds,
                               Integer purgeAfterDays) {

    /**
     * 注销到物理清理的保留期,默认 90 天(约 3 个月)。
     *
     * <p>留着这段时间是为了"删错了还能救回来":期间数据一行不动,只是不允许登录;
     * 到期后由任务真删,那时只能靠导出留的存档。0 表示注销后立刻可清理(不推荐)。
     */
    private static final int DEFAULT_PURGE_AFTER_DAYS = 90;

    public TenantProperties {
        publicPaths = publicPaths == null ? List.of() : List.copyOf(publicPaths);
        tenantStatusCacheSeconds = tenantStatusCacheSeconds == null ? 60 : tenantStatusCacheSeconds;
        purgeAfterDays = purgeAfterDays == null ? DEFAULT_PURGE_AFTER_DAYS : purgeAfterDays;
        if (purgeAfterDays < 0) {
            throw new IllegalArgumentException("minimall.tenant.purge-after-days 不能为负数");
        }
    }
}
