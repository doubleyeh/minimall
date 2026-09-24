package com.minimall.sys.domain.repository;

import com.minimall.sys.domain.SysWxPayConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 微信支付配置仓储(平台级表,不受租户过滤)。
 *
 * <p>继承 {@code QuerydslPredicateExecutor} 是为了列表页的"租户 + 状态"动态条件。
 */
public interface SysWxPayConfigRepository extends JpaRepository<SysWxPayConfig, Long>,
        QuerydslPredicateExecutor<SysWxPayConfig> {

    /** 每租户一行(uk_tenant),支付与登录都按它取配置。 */
    Optional<SysWxPayConfig> findByTenantId(Long tenantId);

    /** 列表页按页内租户批量取配置,避免逐行查库。 */
    List<SysWxPayConfig> findByTenantIdIn(Collection<Long> tenantIds);
}
