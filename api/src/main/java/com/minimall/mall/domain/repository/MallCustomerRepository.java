package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.QMallCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Optional;

/**
 * 商城客户仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallCustomerRepository extends JpaRepository<MallCustomer, Long>,
        QuerydslPredicateExecutor<MallCustomer> {

    @Override
    default Optional<MallCustomer> findById(Long id) {
        return findOne(QMallCustomer.mallCustomer.id.eq(id));
    }

    /**
     * 按 (租户, openid) 定位客户 —— 微信登录的第一步(3.1)。
     *
     * <p>方法名里显式带上 tenantId:登录时租户刚从请求里解析出来,`TenantContext` 与过滤器
     * 可能还没来得及绑定新值,靠方法参数比靠上下文更可靠(与登录查用户同理)。
     */
    Optional<MallCustomer> findByTenantIdAndOpenid(Long tenantId, String openid);

    long countByMemberLevelId(Long memberLevelId);
}
