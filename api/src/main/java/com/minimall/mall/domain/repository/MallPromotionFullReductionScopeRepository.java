package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallPromotionFullReductionScope;
import com.minimall.mall.domain.QMallPromotionFullReductionScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 满减活动范围仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallPromotionFullReductionScopeRepository
        extends JpaRepository<MallPromotionFullReductionScope, Long>,
        QuerydslPredicateExecutor<MallPromotionFullReductionScope> {

    @Override
    default Optional<MallPromotionFullReductionScope> findById(Long id) {
        return findOne(QMallPromotionFullReductionScope.mallPromotionFullReductionScope.id.eq(id));
    }

    List<MallPromotionFullReductionScope> findByActivityId(Long activityId);

    List<MallPromotionFullReductionScope> findByActivityIdIn(Collection<Long> activityIds);

    long deleteByActivityId(Long activityId);
}
