package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallOrderItem;
import com.minimall.mall.domain.QMallOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 订单明细仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallOrderItemRepository extends JpaRepository<MallOrderItem, Long>,
        QuerydslPredicateExecutor<MallOrderItem> {

    @Override
    default Optional<MallOrderItem> findById(Long id) {
        return findOne(QMallOrderItem.mallOrderItem.id.eq(id));
    }

    List<MallOrderItem> findByOrderIdOrderByIdAsc(Long orderId);

    List<MallOrderItem> findByOrderIdInOrderByIdAsc(Collection<Long> orderIds);

    long countByGoodsId(Long goodsId);
}
