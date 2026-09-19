package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallCustomerAddress;
import com.minimall.mall.domain.QMallCustomerAddress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 客户收货地址仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallCustomerAddressRepository extends JpaRepository<MallCustomerAddress, Long>,
        QuerydslPredicateExecutor<MallCustomerAddress> {

    @Override
    default Optional<MallCustomerAddress> findById(Long id) {
        return findOne(QMallCustomerAddress.mallCustomerAddress.id.eq(id));
    }

    List<MallCustomerAddress> findByCustomerIdOrderByIsDefaultDescIdDesc(Long customerId);

    /** 下单时取默认地址;没有默认地址时前端会让用户自己选。 */
    Optional<MallCustomerAddress> findByCustomerIdAndIsDefault(Long customerId, Integer isDefault);

    /**
     * 把该客户的所有地址取消默认。
     *
     * <p>配合"设某条为默认"使用,两步在同一事务内完成 —— 库上没有部分唯一索引,
     * "同一客户至多一条默认地址"只能靠这个固定顺序保证。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallCustomerAddress a set a.isDefault = 0 where a.customerId = :customerId and a.tenantId = :tenantId")
    int clearDefault(@Param("customerId") Long customerId, @Param("tenantId") Long tenantId);

    long countByCustomerId(Long customerId);
}
