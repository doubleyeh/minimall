package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallPointsLog;
import com.minimall.mall.domain.QMallPointsLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 积分流水仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallPointsLogRepository extends JpaRepository<MallPointsLog, Long>,
        QuerydslPredicateExecutor<MallPointsLog> {

    @Override
    default Optional<MallPointsLog> findById(Long id) {
        return findOne(QMallPointsLog.mallPointsLog.id.eq(id));
    }

    List<MallPointsLog> findByCustomerIdOrderByIdDesc(Long customerId);

    /** 发放幂等:同一订单只能发一次。 */
    boolean existsByBizTypeAndBizId(Integer bizType, Long bizId);

    /** 退款扣回幂等:同一售后单只能扣一次。 */
    boolean existsByBizTypeAndBizRefId(Integer bizType, Long bizRefId);

    /**
     * 按 (类型, 业务ID) 汇总变动量。
     *
     * <p>退款扣回要同时用两个方向:发放量({@code biz_type=1})当分母、
     * 已扣回量({@code biz_type=3})当已用额度,保证多笔部分退款累计不超过发放值。
     *
     * @return 无记录时返回 null
     */
    @Query("select sum(l.changePoints) from MallPointsLog l "
            + "where l.tenantId = :tenantId and l.customerId = :customerId "
            + "and l.bizType = :bizType and l.bizId = :bizId")
    Long sumChangePoints(@Param("tenantId") Long tenantId, @Param("customerId") Long customerId,
                         @Param("bizType") Integer bizType, @Param("bizId") Long bizId);
}
