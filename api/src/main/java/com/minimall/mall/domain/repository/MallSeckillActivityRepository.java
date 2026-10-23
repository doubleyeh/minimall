package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallSeckillActivity;
import com.minimall.mall.domain.QMallSeckillActivity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 秒杀活动仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 *
 * <p>三个状态迁移都是<b>条件更新</b>:受影响行数为 0 表示"别人已经推进过了"
 * (多实例、手动触发、任务重跑都会重复执行同一段)。不要改成"先查状态再更新"。
 */
public interface MallSeckillActivityRepository extends JpaRepository<MallSeckillActivity, Long>,
        QuerydslPredicateExecutor<MallSeckillActivity> {

    @Override
    default Optional<MallSeckillActivity> findById(Long id) {
        return findOne(QMallSeckillActivity.mallSeckillActivity.id.eq(id));
    }

    /** 客户端可见的场次:待开始与进行中,且还没结束。 */
    @Query("select a from MallSeckillActivity a "
            + "where a.status in (1, 2) and a.endTime > :now order by a.startTime asc, a.id asc")
    List<MallSeckillActivity> findVisible(@Param("now") LocalDateTime now);

    /** 到点该开始的活动(定时任务用,租户过滤器保证只扫当前租户)。 */
    @Query("select a from MallSeckillActivity a "
            + "where a.status = 1 and a.startTime <= :now order by a.id asc")
    List<MallSeckillActivity> findDueToStart(@Param("now") LocalDateTime now);

    /**
     * 到点该结束的活动。
     *
     * <p>带 {@code status = 1}(待开始)是刻意的:服务停了一段时间、错过了开始时间又错过了结束时间的活动,
     * 不能永远停在"待开始"——它的库存还冻着。这里直接收尾并解冻。
     */
    @Query("select a from MallSeckillActivity a "
            + "where a.status in (1, 2) and a.endTime <= :now order by a.id asc")
    List<MallSeckillActivity> findDueToEnd(@Param("now") LocalDateTime now);

    /** 草稿 → 待开始。返回 0 表示已被别人发布或不是草稿。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSeckillActivity a set a.status = 1 "
            + "where a.id = :id and a.tenantId = :tenantId and a.status = 0")
    int markPending(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /** 待开始 → 进行中。返回 0 表示已被别的实例推进。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSeckillActivity a set a.status = 2 "
            + "where a.id = :id and a.tenantId = :tenantId and a.status = 1 "
            + "and a.startTime <= :now and a.endTime > :now")
    int markRunning(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("now") LocalDateTime now);

    /** 待开始/进行中 → 已结束。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSeckillActivity a set a.status = 3 "
            + "where a.id = :id and a.tenantId = :tenantId and a.status in (1, 2) and a.endTime <= :now")
    int markEnded(@Param("id") Long id, @Param("tenantId") Long tenantId, @Param("now") LocalDateTime now);

    /** 草稿/待开始/进行中 → 已取消。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSeckillActivity a set a.status = 4 "
            + "where a.id = :id and a.tenantId = :tenantId and a.status in (0, 1, 2)")
    int markCancelled(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /** 同一 SKU 上是否有时间重叠的已发布活动(发布校验用)。 */
    @Query("select count(a) from MallSeckillActivity a, MallSeckillItem i "
            + "where i.activityId = a.id and i.skuId = :skuId "
            + "and a.status in (1, 2) and a.startTime < :endTime and a.endTime > :startTime")
    long countOverlapping(@Param("skuId") Long skuId,
                          @Param("startTime") LocalDateTime startTime,
                          @Param("endTime") LocalDateTime endTime);
}
