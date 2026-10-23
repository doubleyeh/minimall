package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallSeckillBuyer;
import com.minimall.mall.domain.QMallSeckillBuyer;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 秒杀限购账本仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 *
 * <p>限购的真相在这里:Redis 只负责让超限请求不进数据库,真正"买了几件"以 {@code bought_count} 为准。
 * 锁的粒度是"这个人 × 这个商品"一行,不同用户互不阻塞。
 */
public interface MallSeckillBuyerRepository extends JpaRepository<MallSeckillBuyer, Long>,
        QuerydslPredicateExecutor<MallSeckillBuyer> {

    @Override
    default Optional<MallSeckillBuyer> findById(Long id) {
        return findOne(QMallSeckillBuyer.mallSeckillBuyer.id.eq(id));
    }

    /**
     * 保证账本行存在(native,自带 id 与时间戳,不经过实体生命周期)。
     * 已存在时静默跳过,所以可以每单都先调一次。
     */
    @Modifying
    @Query(value = "insert ignore into mall_seckill_buyer "
            + "(id, tenant_id, item_id, customer_id, bought_count, create_time, update_time) "
            + "values (:id, :tenantId, :itemId, :customerId, 0, now(), now())", nativeQuery = true)
    int ensureExists(@Param("id") Long id, @Param("tenantId") Long tenantId,
                     @Param("itemId") Long itemId, @Param("customerId") Long customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from MallSeckillBuyer b where b.itemId = :itemId and b.customerId = :customerId")
    Optional<MallSeckillBuyer> findForUpdate(@Param("itemId") Long itemId,
                                             @Param("customerId") Long customerId);

    /** 只读查询(详情页展示"已购 N 件"),不加锁。 */
    Optional<MallSeckillBuyer> findByItemIdAndCustomerId(Long itemId, Long customerId);

    /** 精确占用:只有"买完不超过限购"才加。返回 0 表示已超限。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSeckillBuyer b set b.boughtCount = b.boughtCount + :qty "
            + "where b.id = :id and b.boughtCount + :qty <= :limit")
    int increase(@Param("id") Long id, @Param("qty") int qty, @Param("limit") int limit);

    /** 取消订单时回退。条件防负数。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSeckillBuyer b set b.boughtCount = b.boughtCount - :qty "
            + "where b.id = :id and b.boughtCount >= :qty")
    int decrease(@Param("id") Long id, @Param("qty") int qty);
}
