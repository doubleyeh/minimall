package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallGrowthLog;
import com.minimall.mall.domain.QMallGrowthLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 成长值流水仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallGrowthLogRepository extends JpaRepository<MallGrowthLog, Long>,
        QuerydslPredicateExecutor<MallGrowthLog> {

    @Override
    default Optional<MallGrowthLog> findById(Long id) {
        return findOne(QMallGrowthLog.mallGrowthLog.id.eq(id));
    }

    /** 滚动窗口内求和 = 当前成长值。无记录时返回 null。 */
    @Query("select sum(g.changeGrowth) from MallGrowthLog g "
            + "where g.customerId = :customerId and g.tenantId = :tenantId and g.createTime > :since")
    Long sumSince(@Param("customerId") Long customerId, @Param("tenantId") Long tenantId,
                  @Param("since") LocalDateTime since);

    /**
     * 有成长值记录的客户 —— 降级任务的重算范围。
     *
     * <p>不做"只挑窗口刚滚过的客户":漏跑一次任务就会漏掉那一天的降级,而漏掉的客户
     * 从此再也不会被重算(除非他又有新流水)。范围大一点是幂等的,漏算不是。
     */
    @Query("select distinct g.customerId from MallGrowthLog g where g.tenantId = :tenantId")
    List<Long> findDistinctCustomerIds(@Param("tenantId") Long tenantId);

    boolean existsByBizTypeAndBizRefId(Integer bizType, Long bizRefId);
}
