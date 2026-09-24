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
 */
@Component
public class WxPayRefundSubmitter {

    private static final Logger log = LoggerFactory.getLogger(WxPayRefundSubmitter.class);

    private final WxPayClient wxPayClient;
    private final MallWxPaymentRepository paymentRepository;
    private final MallWxRefundRepository refundRepository;
    private final TenantLookup tenantLookup;

    public WxPayRefundSubmitter(WxPayClient wxPayClient, MallWxPaymentRepository paymentRepository,
                                MallWxRefundRepository refundRepository, TenantLookup tenantLookup) {
        this.wxPayClient = wxPayClient;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.tenantLookup = tenantLookup;
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
        // afterCommit 时事务已结束、租户上下文已被清空:不自己设回来,带租户过滤的读会查不到
        // 刚提交的那条退款行,表现为"退款静默没提交给微信"
        try {
            TenantContext.callAsTenant(tenantId, false, () -> {
                doSubmit(tenantId, orderId, refundId, refundAmount, reason);
                return null;
            });
        } catch (RuntimeException ex) {
            // 事务已经提交,这里抛出去只会变成看不懂的 TransactionSystemException;
            // 退款行已落库为"申请中",记下来由人工/重试处理
            log.error("提交退款申请失败,需人工处理 refundId={} orderId={}", refundId, orderId, ex);
        }
    }

    private void doSubmit(Long tenantId, Long orderId, Long refundId, BigDecimal refundAmount, String reason) {
        MallWxRefund refund = refundRepository.findById(refundId).orElse(null);
        if (refund == null) {
            log.warn("退款流水不存在,未提交给微信 refundId={}", refundId);
            return;
        }
        TenantSnapshot tenant = tenantLookup.byId(tenantId).orElse(null);
        Optional<MallWxPayment> payment = paymentRepository.findFirstByOrderIdOrderByIdDesc(orderId);
        if (tenant == null || payment.isEmpty()) {
            log.warn("退款缺少租户或支付流水,未提交给微信 refundId={} orderId={}", refundId, orderId);
            return;
        }
        MallWxPayment paid = payment.get();
        WxPayRefundCommand command = new WxPayRefundCommand(tenantId, tenant.tenantCode(),
                paid.getOutTradeNo(), paid.getWxTransactionId(), refund.getOutRefundNo(),
                refundAmount, paid.getPayAmount(), reason);

        // 不在这里写库:afterCommit 时事务已提交,此时的 UPDATE 会落在已提交的连接上而丢失。
        // 微信退款单号由退款回调写入(见 MallWxRefund.wxRefundId),这里只保证申请真的发出去了
        wxPayClient.refund(command).ifPresentOrElse(
                wxRefundId -> log.info("退款申请已提交 refundId={} outRefundNo={} wxRefundId={}",
                        refundId, refund.getOutRefundNo(), wxRefundId),
                () -> log.warn("渠道未返回退款单号,退款仍为申请中 refundId={} outRefundNo={}",
                        refundId, refund.getOutRefundNo()));
    }
}
