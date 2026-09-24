package com.minimall.mall.infra.pay;

import java.util.Optional;

/**
 * 按租户取微信支付凭据(架构文档 4.11 的缓存套路)。
 *
 * <p>接口放在这里、实现放在 sys 侧,与 {@code TenantLookup} 同构:支付侧只依赖"能拿到凭据",
 * 不关心它是从库还是缓存来的。
 */
public interface WxPayConfigProvider {

    /** 取该租户启用的凭据;未配置或已停用返回空。 */
    Optional<WxPayCredentials> byTenantId(Long tenantId);

    /** 配置变更后清缓存。 */
    void evict(Long tenantId);
}
