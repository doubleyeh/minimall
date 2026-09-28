package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallPointsBatch;
import com.minimall.mall.domain.QMallPointsBatch;
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
 * 积分批次仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 *
 * <p><b>消耗与清零的 {@code @Modifying} 是并发安全的唯一落点,不要改成"先查再改"</b>:
 * 条件写进 {@code WHERE},受影响行数为 0 即表示条件不满足(剩余不足或批次已过期)。
 *
 * <p>JPQL 批量更新不经过 Hibernate 过滤器,所以每条语句都显式带上 {@code tenantId}。
 */
public interface MallPointsBatchRepository extends JpaRepository<MallPointsBatch, Long>,
        QuerydslPredicateExecutor<MallPointsBatch> {

    @Override
    default Optional<MallPointsBatch> findById(Long id) {
        return findOne(QMallPointsBatch.mallPointsBatch.id.eq(id));
    }

    /** 可用批次,按 FIFO 排序:先到期的先扣,同过期时间按发放顺序。 */
    @Query("select b from MallPointsBatch b "
            + "where b.customerId = :customerId and b.tenantId = :tenantId "
            + "and b.remainPoints > 0 and b.expireTime > :now "
            + "order by b.expireTime asc, b.id asc")
    List<MallPointsBatch> findUsable(@Param("customerId") Long customerId,
                                     @Param("tenantId") Long tenantId,
                                     @Param("now") LocalDateTime now);

    /**
     * 从某批次扣减积分。
     *
     * @return 受影响行数;0 表示剩余不足(被并发抢走),调用方应接着试下一批
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallPointsBatch b set b.remainPoints = b.remainPoints - :points "
            + "where b.id = :batchId and b.tenantId = :tenantId and b.remainPoints >= :points")
    int consume(@Param("batchId") Long batchId, @Param("tenantId") Long tenantId,
                @Param("points") int points);

    /**
     * 退回原批次(订单关闭)。条件 {@code expireTime > now} 表示**已过期的批次不复活** ——
     * 那部分积分该随过期清零,退回等于凭空续期。
     *
     * @return 受影响行数;0 表示批次已过期,调用方按"退回丢弃"处理
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallPointsBatch b set b.remainPoints = b.remainPoints + :points "
            + "where b.id = :batchId and b.tenantId = :tenantId and b.expireTime > :now")
    int returnToBatch(@Param("batchId") Long batchId, @Param("tenantId") Long tenantId,
                      @Param("points") int points, @Param("now") LocalDateTime now);

    /** 过期任务扫描:已过期且还有剩余的批次。 */
    @Query("select b from MallPointsBatch b "
            + "where b.tenantId = :tenantId and b.remainPoints > 0 and b.expireTime <= :now "
            + "order by b.id asc")
    List<MallPointsBatch> findExpired(@Param("tenantId") Long tenantId,
                                      @Param("now") LocalDateTime now,
                                      Pageable pageable);

    /** 单个客户的已过期批次。用于"懒过期":活跃客户在下单那一刻就把过期积分清掉。 */
    @Query("select b from MallPointsBatch b "
            + "where b.customerId = :customerId and b.tenantId = :tenantId "
            + "and b.remainPoints > 0 and b.expireTime <= :now "
            + "order by b.id asc")
    List<MallPointsBatch> findExpiredByCustomer(@Param("customerId") Long customerId,
                                                @Param("tenantId") Long tenantId,
                                                @Param("now") LocalDateTime now);

    /**
     * 把某批次的剩余清零。条件 {@code remainPoints > 0} 保证幂等:
     * 重复运行不会重复累计"过期了多少"。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallPointsBatch b set b.remainPoints = 0 "
            + "where b.id = :batchId and b.tenantId = :tenantId and b.remainPoints > 0")
    int clearRemain(@Param("batchId") Long batchId, @Param("tenantId") Long tenantId);

    /** 测试与对账用:该客户所有未过期批次的剩余之和,应恒等于 {@code mall_customer.points}。 */
    @Query("select sum(b.remainPoints) from MallPointsBatch b "
            + "where b.customerId = :customerId and b.tenantId = :tenantId and b.expireTime > :now")
    Long sumUsableRemain(@Param("customerId") Long customerId, @Param("tenantId") Long tenantId,
                         @Param("now") LocalDateTime now);
}
