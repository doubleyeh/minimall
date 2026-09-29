package com.minimall.mall.service.support;

import com.minimall.mall.domain.MallWxRefund;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Consumer;

/**
 * 退款提交结果的落库。
 *
 * <p>为什么单独一个 bean:{@link WxPayRefundSubmitter} 跑在 afterCommit 里,那时外层事务已经提交
 * 但**资源还挂在当前线程上**,这里再用默认传播级别会加入那个已提交的事务,写进一个提交过的连接 → 丢。
 * 所以两个方法都用 {@code REQUIRES_NEW},开一个真正的新事务。
 *
 * <p>自己调自己的 {@code @Transactional} 不过代理,所以这段不能写在 submitter 里。
 */
@Component
public class RefundSubmitRecorder {

    private static final Logger log = LoggerFactory.getLogger(RefundSubmitRecorder.class);

    private final MallWxRefundRepository refundRepository;

    public RefundSubmitRecorder(MallWxRefundRepository refundRepository) {
        this.refundRepository = refundRepository;
    }

    /**
     * 渠道已受理:写下微信退款单号。状态回到"申请中" —— 最终态由退款回调落定。
     *
     * <p>这个单号同时是"已经送出去了"的标记:重试任务靠它区分"在等回调"与"没提交成功"。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accepted(Long refundId, String wxRefundId) {
        update(refundId, refund -> {
            refund.setRefundStatus(MallWxRefund.REFUND_STATUS_APPLYING);
            refund.setWxRefundId(wxRefundId);
        });
    }

    /** 没送到渠道:置"提交失败",等重试任务再投。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void submitFailed(Long refundId) {
        update(refundId, refund -> refund.setRefundStatus(MallWxRefund.REFUND_STATUS_SUBMIT_FAILED));
    }

    private void update(Long refundId, Consumer<MallWxRefund> change) {
        MallWxRefund refund = refundRepository.findById(refundId).orElse(null);
        if (refund == null) {
            // 读不到时静默跳过会让"提交失败/已受理"这条线索彻底断掉,必须出声
            log.warn("退款提交结果写不进去:找不到退款流水 refundId={}", refundId);
            return;
        }
        change.accept(refund);
        refundRepository.save(refund);
    }
}
