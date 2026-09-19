package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallOrderStatusLog;
import com.minimall.mall.domain.QMallOrderStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 订单状态流水仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallOrderStatusLogRepository extends JpaRepository<MallOrderStatusLog, Long>,
        QuerydslPredicateExecutor<MallOrderStatusLog> {

    @Override
    default Optional<MallOrderStatusLog> findById(Long id) {
        return findOne(QMallOrderStatusLog.mallOrderStatusLog.id.eq(id));
    }

    List<MallOrderStatusLog> findByOrderIdOrderByIdAsc(Long orderId);
}
