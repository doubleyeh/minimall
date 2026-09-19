package com.minimall.sys.domain.repository;

import com.minimall.sys.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 租户仓储(架构文档 4.6.1:{@code tenant} 是平台级表,不受租户过滤)。
 *
 * <p>继承 {@code QuerydslPredicateExecutor} 是为了租户列表的"编码 + 状态"动态条件(1.1)。
 */
public interface TenantRepository extends JpaRepository<Tenant, Long>, QuerydslPredicateExecutor<Tenant> {

    /** 登录时按 tenantCode 定位租户(7.1.1 第 1 步);命中 4.11 的状态缓存后不会每次都查库。 */
    Optional<Tenant> findByTenantCode(String tenantCode);

    /** 4.8.1:平台改套餐内容时,找出所有绑定该套餐的租户。 */
    List<Tenant> findByPackageId(Long packageId);

    /** 套餐列表上显示"还有多少租户在用"(有租户在用的套餐只能禁用不能删,5.6)。 */
    long countByPackageId(Long packageId);
}
