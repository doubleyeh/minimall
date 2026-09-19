package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallPromotionFullReduction;
import com.minimall.mall.domain.QMallPromotionFullReduction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 满减活动仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallPromotionFullReductionRepository extends JpaRepository<MallPromotionFullReduction, Long>,
        QuerydslPredicateExecutor<MallPromotionFullReduction> {

    @Override
    default Optional<MallPromotionFullReduction> findById(Long id) {
        return findOne(QMallPromotionFullReduction.mallPromotionFullReduction.id.eq(id));
    }

    /** 结算时参与的满减活动:进行中且在有效期内(3.5)。 */
    @Query("select p from MallPromotionFullReduction p where p.status = 1 "
            + "and p.validStartTime <= :now and p.validEndTime >= :now order by p.id asc")
    List<MallPromotionFullReduction> findActive(@Param("now") LocalDateTime now);
}
