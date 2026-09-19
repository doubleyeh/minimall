package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallCouponRecord;
import com.minimall.mall.domain.QMallCouponRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 优惠券领取/使用记录仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallCouponRecordRepository extends JpaRepository<MallCouponRecord, Long>,
        QuerydslPredicateExecutor<MallCouponRecord> {

    @Override
    default Optional<MallCouponRecord> findById(Long id) {
        return findOne(QMallCouponRecord.mallCouponRecord.id.eq(id));
    }

    List<MallCouponRecord> findByCustomerIdOrderByIdDesc(Long customerId);

    long countByCouponIdAndCustomerId(Long couponId, Long customerId);

    List<MallCouponRecord> findByOrderId(Long orderId);

    /**
     * 下单核销(3.3 第 6 步):把券置为"已使用"并回填订单。
     *
     * <p>条件 {@code status = 1} 是并发保护:同一张券被两个订单同时使用时,
     * 只有一条更新会命中(受影响行数 0 的那单必须回滚),防止一张券抵扣两单。
     *
     * @return 受影响行数;0 表示券已被使用或不属于该客户
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallCouponRecord r set r.status = 2, r.orderId = :orderId, r.useTime = :useTime "
            + "where r.id = :recordId and r.customerId = :customerId and r.tenantId = :tenantId and r.status = 1")
    int useForOrder(@Param("recordId") Long recordId, @Param("customerId") Long customerId,
                    @Param("tenantId") Long tenantId, @Param("orderId") Long orderId,
                    @Param("useTime") LocalDateTime useTime);

    /** 订单取消时退回优惠券(3.4:未支付关闭要把券还给买家,否则券白扣了)。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallCouponRecord r set r.status = 1, r.orderId = null, r.useTime = null "
            + "where r.orderId = :orderId and r.tenantId = :tenantId and r.status = 2")
    int releaseByOrder(@Param("orderId") Long orderId, @Param("tenantId") Long tenantId);

    /** 过期清理任务:把已过期且未使用的券置为"已过期"(3.10)。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallCouponRecord r set r.status = 3 where r.status = 1 and r.couponId in "
            + "(select c.id from MallCoupon c where c.validEndTime < :now) and r.tenantId = :tenantId")
    int expireOutdated(@Param("now") LocalDateTime now, @Param("tenantId") Long tenantId);
}
