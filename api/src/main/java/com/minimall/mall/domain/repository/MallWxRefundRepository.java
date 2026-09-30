package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallWxRefund;
import com.minimall.mall.domain.QMallWxRefund;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 微信退款流水仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallWxRefundRepository extends JpaRepository<MallWxRefund, Long>,
        QuerydslPredicateExecutor<MallWxRefund> {

    @Override
    default Optional<MallWxRefund> findById(Long id) {
        return findOne(QMallWxRefund.mallWxRefund.id.eq(id));
    }

    Optional<MallWxRefund> findByTenantIdAndOutRefundNo(Long tenantId, String outRefundNo);

    Optional<MallWxRefund> findByWxRefundId(String wxRefundId);

    List<MallWxRefund> findByAfterSaleIdOrderByIdAsc(Long afterSaleId);

    /** 指定状态的退款单(重试任务用)。 */
    List<MallWxRefund> findByRefundStatusOrderByIdAsc(int refundStatus, Pageable pageable);

    /**
     * 卡在"申请中"、又没有微信退款单号、且已超过缓冲时间的退款单。
     *
     * <p>这些多半是提交那一步没走完(进程被杀、afterCommit 没跑)—— 不会有任何回调来收尾,只能重投。
     */
    List<MallWxRefund> findByRefundStatusAndWxRefundIdIsNullAndCreateTimeBeforeOrderByIdAsc(
            int refundStatus, LocalDateTime before, Pageable pageable);

    /**
     * 退款成功额(销售统计用):按**退款成功时间**({@code callback_time})落区间。
     *
     * <p>与销售额的时间轴不同(那个按订单的下单时间),所以两个数字只用于看量级,
     * 不能当严格对账 —— 页面要提示这一点。区间内没有退款时返回 {@code null}。
     */
    @Query("select sum(r.refundAmount) from MallWxRefund r "
            + "where r.refundStatus = " + MallWxRefund.REFUND_STATUS_SUCCESS
            + " and r.callbackTime >= :startTime and r.callbackTime <= :endTime")
    BigDecimal sumSucceededAmount(@Param("startTime") LocalDateTime startTime,
                                  @Param("endTime") LocalDateTime endTime);
}
