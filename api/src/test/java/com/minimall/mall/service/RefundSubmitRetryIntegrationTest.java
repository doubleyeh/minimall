package com.minimall.mall.service;

import com.minimall.mall.domain.MallWxRefund;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
import com.minimall.mall.service.support.WxPayCallbackFixture;
import com.minimall.support.StubWxPayHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款申请的提交与重投(商城设计文档 3.9)。
 *
 * <p>退款是事务提交后异步发的,那一步失败以前只是打一条日志:退款单永远停在"申请中",
 * 买家收不到钱、运营也看不出来。这里钉住"失败要落状态、并且能被重投回来"。
 */
class RefundSubmitRetryIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private OrderAdminService orderAdminService;
    @Autowired
    private MallScheduledTasks tasks;
    @Autowired
    private MallWxRefundRepository refundRepository;
    @Autowired
    private WxPayCallbackFixture payConfig;
    @Autowired
    private StubWxPayHttpClient wxStub;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void ensurePayConfigured() {
        payConfig.ensureConfig("platform");
    }

    @AfterEach
    void clearTaskState() {
        redis.delete(redis.keys("task:lock:*"));
        wxStub.reset();
        inTenant(() -> {
            refundsOf(null).forEach(refundRepository::delete);
            return null;
        });
    }

    @Test
    @DisplayName("渠道没受理:退款单落「提交失败」,而不是一直假装申请中")
    void recordsSubmitFailure() {
        Long orderId = paidThenCancelled(order -> wxStub.setRefundRejected(true));

        MallWxRefund refund = onlyRefundOf(orderId);
        assertThat(refund.getRefundStatus())
                .isEqualTo(MallWxRefund.REFUND_STATUS_SUBMIT_FAILED);
        assertThat(refund.getWxRefundId()).as("没受理就不该有微信退款单号").isNull();
    }

    @Test
    @DisplayName("重投:提交失败的单被任务捡起来,受理后写上微信退款单号")
    void retrySubmitsAgainAndRecordsAcceptance() {
        Long orderId = paidThenCancelled(order -> wxStub.setRefundRejected(true));
        assertThat(onlyRefundOf(orderId).getRefundStatus())
                .isEqualTo(MallWxRefund.REFUND_STATUS_SUBMIT_FAILED);

        // 渠道恢复,重投应当受理
        wxStub.setRefundRejected(false);
        tasks.retryRefundSubmit();

        MallWxRefund refund = onlyRefundOf(orderId);
        assertThat(refund.getRefundStatus()).as("受理后回到申请中,等退款回调落最终态")
                .isEqualTo(MallWxRefund.REFUND_STATUS_APPLYING);
        assertThat(refund.getWxRefundId()).as("微信退款单号要落库 —— 它也是「已送出去」的标记").isNotNull();
    }

    @Test
    @DisplayName("提交就成功:同样要写下微信退款单号")
    void recordsAcceptanceOnFirstSubmit() {
        Long orderId = paidThenCancelled(order -> { });

        MallWxRefund refund = onlyRefundOf(orderId);
        assertThat(refund.getRefundStatus()).isEqualTo(MallWxRefund.REFUND_STATUS_APPLYING);
        assertThat(refund.getWxRefundId()).isNotNull();
    }

    /** 下单 → 支付 → 商家取消(取消会创建退款并提交),提交前先按 {@code prepare} 摆好渠道状态。 */
    private Long paidThenCancelled(Consumer<Long> prepare) {
        Long orderId = createOrder(customerId, addressId, 1).orderId();
        BigDecimal payAmount = inTenant(() -> orderRepository.findById(orderId).orElseThrow().getPayAmount());
        String orderNo = inTenant(() -> orderRepository.findById(orderId).orElseThrow().getOrderNo());
        payConfig.paySuccess("platform", orderNo, payAmount);

        prepare.accept(orderId);
        inTenant(() -> {
            orderAdminService.cancel(orderId, "集成测试取消");
            return null;
        });
        return orderId;
    }

    private MallWxRefund onlyRefundOf(Long orderId) {
        return inTenant(() -> refundsOf(orderId).stream().findFirst().orElseThrow());
    }

    private List<MallWxRefund> refundsOf(Long orderId) {
        return refundRepository.findAll().stream()
                .filter(refund -> orderId == null || orderId.equals(refund.getOrderId()))
                .toList();
    }
}
