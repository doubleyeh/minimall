package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallCoupon;
import com.minimall.mall.domain.QMallCoupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 优惠券仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallCouponRepository extends JpaRepository<MallCoupon, Long>,
        QuerydslPredicateExecutor<MallCoupon> {

    @Override
    default Optional<MallCoupon> findById(Long id) {
        return findOne(QMallCoupon.mallCoupon.id.eq(id));
    }

    /**
     * 领取时占用一个名额(3.10)。
     *
     * <p><b>这条 SQL 是"不超发"的唯一保证</b>:条件里的 {@code receivedCount < totalCount}
     * 让"判断"和"占用"成为一次原子操作。如果改成"先查剩余数量、再判断、再更新",
     * 并发领取时两个请求都会通过判断,然后各自加一 —— 超发的券是白送的营销预算。
     *
     * @return 受影响行数;0 表示已领完或券已停用
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallCoupon c set c.receivedCount = c.receivedCount + 1 "
            + "where c.id = :couponId and c.tenantId = :tenantId "
            + "and c.status = 1 and c.receivedCount < c.totalCount")
    int claimOne(@Param("couponId") Long couponId, @Param("tenantId") Long tenantId);

    /** 可领取的券(进行中、在有效期内)—— 小程序领券列表。 */
    @Query("select c from MallCoupon c where c.status = 1 and c.validStartTime <= :now and c.validEndTime >= :now "
            + "order by c.id desc")
    List<MallCoupon> findClaimable(@Param("now") LocalDateTime now);
}
