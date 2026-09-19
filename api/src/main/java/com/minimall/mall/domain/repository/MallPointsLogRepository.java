package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallPointsLog;
import com.minimall.mall.domain.QMallPointsLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 积分流水仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallPointsLogRepository extends JpaRepository<MallPointsLog, Long>,
        QuerydslPredicateExecutor<MallPointsLog> {

    @Override
    default Optional<MallPointsLog> findById(Long id) {
        return findOne(QMallPointsLog.mallPointsLog.id.eq(id));
    }

    List<MallPointsLog> findByCustomerIdOrderByIdDesc(Long customerId);
}
