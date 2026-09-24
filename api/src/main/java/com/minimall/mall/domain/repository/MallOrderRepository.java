package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.QMallOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 订单仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 *
 * <p>下面两个"扫描超时订单"的查询是 JPQL 查询(不是批量更新),所以**会经过 Hibernate 过滤器**:
 * 定时任务用 {@code TenantContext.runAsTenant} 逐租户执行时,只会扫到该租户的订单(见 6.2)。
 */
public interface MallOrderRepository extends JpaRepository<MallOrder, Long>,
        QuerydslPredicateExecutor<MallOrder> {

    @Override
    default Optional<MallOrder> findById(Long id) {
        return findOne(QMallOrder.mallOrder.id.eq(id));
    }

    Optional<MallOrder> findByTenantIdAndOrderNo(Long tenantId, String orderNo);

    /**
     * 超时未支付且需要关闭的订单(每分钟任务,3.4)。
     *
     * @param status   待支付状态值
     * @param deadline 下单时间早于此刻的才算超时
     */
    @Query("select o from MallOrder o where o.status = :status and o.createTime < :deadline order by o.id asc")
    List<MallOrder> findTimeoutPendingOrders(@Param("status") int status,
                                             @Param("deadline") LocalDateTime deadline,
                                             Pageable pageable);

    /** 已发货超过 N 天、需要自动确认收货的订单(每小时任务,3.4)。 */
    @Query("select o from MallOrder o where o.status = :status and o.shipTime is not null and o.shipTime < :deadline "
            + "order by o.id asc")
    List<MallOrder> findAutoReceiveOrders(@Param("status") int status,
                                          @Param("deadline") LocalDateTime deadline,
                                          Pageable pageable);

    long countByCustomerIdAndStatus(Long customerId, Integer status);

    /**
     * 关闭/取消订单时把状态落库。
     *
     * <p>为什么要显式 UPDATE 而不是"改实体等脏检查":超时关闭是**批处理**,
     * 而同一批里的库存/优惠券更新是 {@code @Modifying(clearAutomatically = true)},
     * 会清空持久化上下文 —— 第 2 个及之后的订单随即变成游离对象,此时 setStatus 只改内存,
     * 提交时不会落库(表现为库存退了、日志写了"系统关闭",订单却还是待支付)。
     */
    @Modifying
    @Query("update MallOrder o set o.status = :status, o.cancelTime = :cancelTime, "
            + "o.closeReason = :closeReason where o.id = :orderId and o.tenantId = :tenantId")
    int updateStatusOnClose(@Param("orderId") Long orderId, @Param("tenantId") Long tenantId,
                            @Param("status") int status, @Param("closeReason") Integer closeReason,
                            @Param("cancelTime") LocalDateTime cancelTime);
}
