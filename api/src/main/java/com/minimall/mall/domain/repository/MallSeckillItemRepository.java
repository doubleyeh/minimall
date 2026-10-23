package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallSeckillItem;
import com.minimall.mall.domain.QMallSeckillItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 秒杀商品项仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 *
 * <p><b>配额的收支只有两个入口</b>:{@link #consumeQuota} 与 {@link #releaseQuota}。
 * 支付、退款、售后都不改 {@code sold_count} —— 它统计的是"已占用的名额",不是"已付款件数"。
 */
public interface MallSeckillItemRepository extends JpaRepository<MallSeckillItem, Long>,
        QuerydslPredicateExecutor<MallSeckillItem> {

    @Override
    default Optional<MallSeckillItem> findById(Long id) {
        return findOne(QMallSeckillItem.mallSeckillItem.id.eq(id));
    }

    List<MallSeckillItem> findByActivityIdOrderBySortOrderAscIdAsc(Long activityId);

    List<MallSeckillItem> findByActivityIdInOrderBySortOrderAscIdAsc(Collection<Long> activityIds);

    List<MallSeckillItem> findBySkuIdOrderByIdAsc(Long skuId);

    /**
     * 行锁读取。限购校验必须和配额占用在同一把锁下,否则两个人同时越过限购判断。
     *
     * <p>锁的是商品项这一行,不是 SKU —— 秒杀下单不再走 {@code lockStock} 的整行锁,
     * 而配额更新的条件本身就能保证不超卖。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MallSeckillItem i where i.id = :id")
    Optional<MallSeckillItem> findByIdForUpdate(@Param("id") Long id);

    /**
     * 占用配额:{@code sold_count += qty},条件是"占完不超过配额"。
     *
     * @return 受影响行数;0 表示抢光了
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSeckillItem i set i.soldCount = i.soldCount + :qty "
            + "where i.id = :id and i.tenantId = :tenantId and i.soldCount + :qty <= i.seckillStock")
    int consumeQuota(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("qty") int qty);

    /** 回退配额(取消/超时)。条件防负数,重复回退不会再减一次。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSeckillItem i set i.soldCount = i.soldCount - :qty "
            + "where i.id = :id and i.tenantId = :tenantId and i.soldCount >= :qty")
    int releaseQuota(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("qty") int qty);

    /** 该 SKU 上处于待开始/进行中的商品项(判断能否改库存、下架、删除)。 */
    @Query("select i from MallSeckillItem i, MallSeckillActivity a "
            + "where i.activityId = a.id and i.skuId = :skuId and a.status in (1, 2)")
    List<MallSeckillItem> findLockedBySku(@Param("skuId") Long skuId);

    /** 某商品当前可见(待开始/进行中且未结束)的秒杀项,按开场时间升序。 */
    @Query("select i from MallSeckillItem i, MallSeckillActivity a "
            + "where i.activityId = a.id and i.goodsId = :goodsId "
            + "and a.status in (1, 2) and a.endTime > :now "
            + "order by a.startTime asc, i.sortOrder asc, i.id asc")
    List<MallSeckillItem> findVisibleByGoods(@Param("goodsId") Long goodsId,
                                             @Param("now") java.time.LocalDateTime now);
}
