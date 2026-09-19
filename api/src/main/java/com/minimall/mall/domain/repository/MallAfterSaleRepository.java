package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallAfterSale;
import com.minimall.mall.domain.QMallAfterSale;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 售后单仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 *
 * <p>三个超时扫描查询都用 {@code updateTime} 作为"进入当前状态的时间"。
 * 这不是偷懒:状态每次变更都会刷新 {@code update_time}({@code @LastModifiedDate}),
 * 而每个超时规则的起点恰好都是"上一次状态变更"(如 status=2 是"商家同意"的那一刻)。
 * 为每种超时单独加一列时间戳会带来"哪一列对应哪个状态"的额外维护成本,
 * 收益只有几毫秒的索引差异。前提是:**状态变更必须走更新实体的方式**(这样才会刷新 update_time)。
 */
public interface MallAfterSaleRepository extends JpaRepository<MallAfterSale, Long>,
        QuerydslPredicateExecutor<MallAfterSale> {

    @Override
    default Optional<MallAfterSale> findById(Long id) {
        return findOne(QMallAfterSale.mallAfterSale.id.eq(id));
    }

    Optional<MallAfterSale> findByTenantIdAndAfterSaleNo(Long tenantId, String afterSaleNo);

    List<MallAfterSale> findByOrderIdOrderByIdDesc(Long orderId);

    List<MallAfterSale> findByOrderItemIdOrderByIdDesc(Long orderItemId);

    /** 某订单明细上是否存在"进行中"的售后单(未到终态),用于限制重复申请(3.9)。 */
    @Query("select count(a) from MallAfterSale a where a.orderItemId = :orderItemId and a.status in :activeStatuses")
    long countActiveByOrderItemId(@Param("orderItemId") Long orderItemId,
                                  @Param("activeStatuses") Collection<Integer> activeStatuses);

    /** 某订单下是否存在进行中的售后单 —— 用于把订单置为"售后中"(3.4/3.9)。 */
    @Query("select count(a) from MallAfterSale a where a.orderId = :orderId and a.status in :activeStatuses")
    long countActiveByOrderId(@Param("orderId") Long orderId,
                              @Param("activeStatuses") Collection<Integer> activeStatuses);

    /**
     * 通用超时扫描:指定状态的售后单,且最后一次状态变更早于 deadline。
     */
    @Query("select a from MallAfterSale a where a.status = :status and a.updateTime < :deadline order by a.id asc")
    List<MallAfterSale> findTimeoutByStatus(@Param("status") int status,
                                            @Param("deadline") LocalDateTime deadline,
                                            Pageable pageable);
}
