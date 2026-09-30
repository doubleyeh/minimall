package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallOrderItem;
import com.minimall.mall.domain.QMallOrderItem;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    /**
     * 商品销售排行(销售统计用):按**下单时间**落区间、且已支付({@code pay_time} 非空)的订单明细聚合。
     *
     * <p>按金额降序取前 N 条,调用方用 {@code Pageable} 传 N(不带排序 —— 排序写在查询里,
     * 因为要按聚合结果排,而 {@code Pageable} 只能按字段名排)。
     *
     * <p>商品名取明细里的**快照名**:改名后历史订单显示的还是下单时的名字。
     */
    @Query("select i.goodsId as goodsId, max(i.goodsName) as goodsName, "
            + "sum(i.quantity) as quantity, sum(i.totalAmount) as amount "
            + "from MallOrderItem i, MallOrder o "
            + "where o.id = i.orderId and o.payTime is not null "
            + "and o.createTime >= :startTime and o.createTime <= :endTime "
            + "group by i.goodsId "
            + "order by sum(i.totalAmount) desc, i.goodsId asc")
    List<TopGoodsRow> findTopGoods(@Param("startTime") LocalDateTime startTime,
                                   @Param("endTime") LocalDateTime endTime,
                                   Pageable pageable);

    /** 商品排行的投影:接口式投影按别名映射,比 {@code Object[]} 不容易串列。 */
    interface TopGoodsRow {

        Long getGoodsId();

        String getGoodsName();

        Long getQuantity();

        BigDecimal getAmount();
    }
}
