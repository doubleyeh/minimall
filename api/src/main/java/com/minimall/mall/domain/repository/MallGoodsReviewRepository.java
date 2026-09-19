package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallGoodsReview;
import com.minimall.mall.domain.QMallGoodsReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Optional;

/**
 * 商品评价仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallGoodsReviewRepository extends JpaRepository<MallGoodsReview, Long>,
        QuerydslPredicateExecutor<MallGoodsReview> {

    @Override
    default Optional<MallGoodsReview> findById(Long id) {
        return findOne(QMallGoodsReview.mallGoodsReview.id.eq(id));
    }

    /** "一个订单明细只能评价一次"的校验入口。 */
    boolean existsByOrderItemId(Long orderItemId);

    long countByGoodsIdAndStatus(Long goodsId, Integer status);
}
