package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallCart;
import com.minimall.mall.domain.QMallCart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 购物车仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallCartRepository extends JpaRepository<MallCart, Long>,
        QuerydslPredicateExecutor<MallCart> {

    @Override
    default Optional<MallCart> findById(Long id) {
        return findOne(QMallCart.mallCart.id.eq(id));
    }

    List<MallCart> findByCustomerIdOrderByIdDesc(Long customerId);

    /**
     * 加购时的"是否已有该 SKU 的一行"判断。
     *
     * <p>注意:**判断之后仍然要靠唯一键兜底**。并发双击加购时两个请求都会查不到,
     * 插入时第二个会被 {@code uk_tenant_customer_sku} 拒绝 —— 捕获唯一键冲突再累加,
     * 是这条路唯一正确的写法(见购物车服务的实现)。
     */
    Optional<MallCart> findByCustomerIdAndSkuId(Long customerId, Long skuId);

    long deleteByCustomerIdAndSkuIdIn(Long customerId, List<Long> skuIds);
}
