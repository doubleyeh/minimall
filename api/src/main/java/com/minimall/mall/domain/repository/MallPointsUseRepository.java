package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallPointsUse;
import com.minimall.mall.domain.QMallPointsUse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 订单抵现批次明细仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallPointsUseRepository extends JpaRepository<MallPointsUse, Long>,
        QuerydslPredicateExecutor<MallPointsUse> {

    @Override
    default Optional<MallPointsUse> findById(Long id) {
        return findOne(QMallPointsUse.mallPointsUse.id.eq(id));
    }

    /** 订单关闭时按此找回占用的批次。退回后删行,所以重复取消查不到东西。 */
    List<MallPointsUse> findByOrderIdAndTenantId(Long orderId, Long tenantId);
}
