package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallWxPayment;
import com.minimall.mall.domain.QMallWxPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Optional;

/**
 * 微信支付流水仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallWxPaymentRepository extends JpaRepository<MallWxPayment, Long>,
        QuerydslPredicateExecutor<MallWxPayment> {

    @Override
    default Optional<MallWxPayment> findById(Long id) {
        return findOne(QMallWxPayment.mallWxPayment.id.eq(id));
    }

    Optional<MallWxPayment> findByTenantIdAndOutTradeNo(Long tenantId, String outTradeNo);

    /**
     * 按商户订单号查支付流水,**不带租户条件**。
     *
     * <p>只给支付回调使用:回调来自微信服务器,那时没有租户上下文,必须先按订单号定位到流水、
     * 才知道是哪个租户的。订单号全局唯一(见 {@code OrderNumberGenerator}),所以不会串单。
     * 业务代码不要用它 —— 它绕过了租户隔离。
     */
    Optional<MallWxPayment> findByOutTradeNo(String outTradeNo);

    /**
     * 回调幂等判断的第一步(3.8):按微信支付订单号找已有的成功记录。
     *
     * <p>第一步只能是"查",最终防线是库上的唯一键 {@code uk_wx_transaction} ——
     * 微信并发重复推送时,两个线程可能同时查不到,靠唯一键才能保证只有一条成功记录落库。
     */
    Optional<MallWxPayment> findByWxTransactionId(String wxTransactionId);

    Optional<MallWxPayment> findFirstByOrderIdOrderByIdDesc(Long orderId);
}
