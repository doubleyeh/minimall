package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallAfterSaleLog;
import com.minimall.mall.domain.QMallAfterSaleLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 售后状态流水仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallAfterSaleLogRepository extends JpaRepository<MallAfterSaleLog, Long>,
        QuerydslPredicateExecutor<MallAfterSaleLog> {

    @Override
    default Optional<MallAfterSaleLog> findById(Long id) {
        return findOne(QMallAfterSaleLog.mallAfterSaleLog.id.eq(id));
    }

    List<MallAfterSaleLog> findByAfterSaleIdOrderByIdAsc(Long afterSaleId);
}
