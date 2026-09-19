package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallWxRefund;
import com.minimall.mall.domain.QMallWxRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 微信退款流水仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallWxRefundRepository extends JpaRepository<MallWxRefund, Long>,
        QuerydslPredicateExecutor<MallWxRefund> {

    @Override
    default Optional<MallWxRefund> findById(Long id) {
        return findOne(QMallWxRefund.mallWxRefund.id.eq(id));
    }

    Optional<MallWxRefund> findByTenantIdAndOutRefundNo(Long tenantId, String outRefundNo);

    Optional<MallWxRefund> findByWxRefundId(String wxRefundId);

    List<MallWxRefund> findByAfterSaleIdOrderByIdAsc(Long afterSaleId);
}
