package com.minimall.infra.tenant;

import java.util.Optional;

/**
 * 租户查询(带 4.11 的状态缓存)。
 *
 * <p>接口定义在 infra、实现在 service,理由与 {@link DataScopeProvider} 相同:
 * {@code TenantWebFilter} 属于 infra、必须能在每个请求里做租户校验,但它不该直接依赖
 * 业务查询;把"怎么查、怎么缓存"放进实现,过滤器只依赖这个接口。
 * 这样也满足第 3 节的依赖方向:infra 不反向依赖 service。
 */
public interface TenantLookup {

    Optional<TenantSnapshot> byCode(String tenantCode);

    Optional<TenantSnapshot> byId(Long tenantId);

    /**
     * 让缓存失效(禁用/启用租户、改有效期后调用)。
     *
     * <p>4.11 的"立即生效"就靠这一步:先删缓存再落库,该租户所有用户的下一个请求
     * 就会重新查库拿到新状态,被拦在业务逻辑之外。60 秒 TTL 只是"万一漏删"的兜底。
     */
    void evict(TenantSnapshot snapshot);
}
