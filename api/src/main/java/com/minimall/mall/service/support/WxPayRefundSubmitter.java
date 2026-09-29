package com.minimall.mall.service.support;

import com.minimall.infra.tenant.TenantContext;
import com.minimall.infra.tenant.TenantLookup;
import com.minimall.infra.tenant.TenantSnapshot;
import com.minimall.mall.domain.MallWxPayment;
import com.minimall.mall.domain.MallWxRefund;
import com.minimall.mall.domain.repository.MallWxPaymentRepository;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
import com.minimall.mall.infra.pay.WxPayClient;
import com.minimall.mall.infra.pay.WxPayRefundCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 退款申请的提交(售后退款与商家取消订单共用)。
 *
 * <p>必须在事务提交之后才发:退款是外部网络调用,占着数据库连接等回包会把连接池拖干(3.3)。
 * 提交前退款行已落库为"申请中",提交失败也只是一条待处理的退款,不会把状态写错。
 *
 * <p>提交结果要写回退款单(见 {@link RefundSubmitRecorder}):拿到微信退款单号 = 渠道已受理、等回调;
 * 没拿到 = "提交失败",由重试任务再投(微信按 {@code out_refund_no} 幂等,重投安全)。
 */
@Component
public class WxPayRefundSubmitter {

    private static final Logger log = LoggerFactory.getLogger(WxPayRefundSubmitter.class);

    private final WxPayClient wxPayClient;
    private final MallWxPaymentRepository paymentRepository;
    private final MallWxRefundRepository refundRepository;
    private final TenantLookup tenantLookup;
    private final RefundSubmitRecorder recorder;

    public WxPayRefundSubmitter(WxPayClient wxPayClient, MallWxPaymentRepository paymentRepository,
                                MallWxRefundRepository refundRepository, TenantLookup tenantLookup,
                                RefundSubmitRecorder recorder) {
        this.wxPayClient = wxPayClient;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.tenantLookup = tenantLookup;
        this.recorder = recorder;
    }

    /** 注册 afterCommit;没有事务时直接提交。 */
    public void submitAfterCommit(Long tenantId, Long orderId, Long refundId,
                                  BigDecimal refundAmount, String reason) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(tenantId, orderId, refundId, refundAmount, reason);
                }
            });
            return;
        }
        submit(tenantId, orderId, refundId, refundAmount, reason);
    }

    private void submit(Long tenantId, Long orderId, Long refundId, BigDecimal refundAmount, String reason) {
        String wxRefundId;
        try {
            wxRefundId = TenantContext.callAsTenant(tenantId, false,
                    () -> doSubmit(tenantId, orderId, refundId, refundAmount, reason));
        } catch (RuntimeException ex) {
            log.error("提交退款申请失败,已置为待重试 refundId={} orderId={}", refundId, orderId, ex);
            recordSubmitFailed(tenantId, refundId);
            return;
        }
        if (wxRefundId == null) {
            log.error("渠道未受理退款(没有退款单号),已置为待重试 refundId={} orderId={}", refundId, orderId);
            recordSubmitFailed(tenantId, refundId);
            return;
        }
        log.info("退款申请已受理 refundId={} wxRefundId={}", refundId, wxRefundId);
        inTenant(tenantId, refundId, () -> recorder.accepted(refundId, wxRefundId));
    }

    /**
     * 写回"提交失败"。
     *
     * <p>写库本身也可能失败,那时只能记日志 —— 不能让它把最初的失败原因盖掉。
     */
    private void recordSubmitFailed(Long tenantId, Long refundId) {
        inTenant(tenantId, refundId, () -> recorder.submitFailed(refundId));
    }

    /** 结果写库要重新进租户上下文:afterCommit 时那里已经被清空了。 */
    private void inTenant(Long tenantId, Long refundId, Runnable action) {
        try {
            TenantContext.callAsTenant(tenantId, false, () -> {
                action.run();
                return null;
            });
        } catch (RuntimeException ex) {
            log.error("退款提交结果写库失败,需人工处理 refundId={}", refundId, ex);
        }
    }

    /**
     * @return 微信退款单号;{@code null} 表示渠道没受理
     */
    private String doSubmit(Long tenantId, Long orderId, Long refundId, BigDecimal refundAmount, String reason) {
        MallWxRefund refund = refundRepository.findById(refundId).orElse(null);
        if (refund == null) {
            throw new IllegalStateException("退款流水不存在 refundId=" + refundId);
        }
        TenantSnapshot tenant = tenantLookup.byId(tenantId).orElse(null);
        Optional<MallWxPayment> payment = paymentRepository.findFirstByOrderIdOrderByIdDesc(orderId);
        if (tenant == null || payment.isEmpty()) {
            throw new IllegalStateException(
                    "退款缺少租户或支付流水 refundId=" + refundId + " orderId=" + orderId);
        }
        MallWxPayment paid = payment.get();
        WxPayRefundCommand command = new WxPayRefundCommand(tenantId, tenant.tenantCode(),
                paid.getOutTradeNo(), paid.getWxTransactionId(), refund.getOutRefundNo(),
                refundAmount, paid.getPayAmount(), reason);

        return wxPayClient.refund(command).orElse(null);
    }
}
