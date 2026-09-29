package com.minimall.mall.service;

import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.MallWxPayment;
import com.minimall.mall.service.support.WxPayCallbackFixture;
import com.minimall.support.StubWxPayHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时查单:把"已关闭、其实钱已经收了"的订单补回来(商城设计文档 3.8)。
 *
 * <p>迟到的回调会自己把订单置回待发货;这里覆盖的是**回调丢了**的情况 ——
 * 那时没有任何人会知道这笔钱到账了,只有主动查单能发现。
 */
class ClosedPaidOrderReconcileIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private MallScheduledTasks tasks;
    @Autowired
    private StubWxPayHttpClient wxStub;
    @Autowired
    private WxPayCallbackFixture payConfig;
    @Autowired
    private StringRedisTemplate redis;

    /** 查单要先能拿到凭据:平台租户没有支付配置时 WxPayClient 连请求都不会发。 */
    @BeforeEach
    void ensurePayConfigured() {
        payConfig.ensureConfig("platform");
    }

    @AfterEach
    void clearTaskState() {
        redis.delete(redis.keys("task:lock:*"));
        wxStub.reset();
    }

    @Test
    @DisplayName("查单确认已支付:订单从已关闭置回待发货,并扣掉实库存")
    void settlesClosedOrderWhenPaid() {
        Long orderId = closedOrderThatWasPrepaid();
        int[] before = skuSnapshot();
        wxStub.setQueryTradeState("SUCCESS");

        tasks.reconcileClosedPaidOrders();

        assertThat(orderStatusOf(orderId)).isEqualTo(MallOrder.STATUS_PENDING_SHIP);
        assertThat(paymentStatusOf(orderId)).as("钱确实收了,支付流水要落成功")
                .isEqualTo(MallWxPayment.PAY_STATUS_SUCCESS);
        int[] after = skuSnapshot();
        assertThat(after[0]).as("复活要重新扣掉实库存").isEqualTo(before[0] - 1);
        assertThat(after[1]).as("锁定在关单时已经退过,再减一次会扣到别的订单头上").isEqualTo(before[1]);
    }

    @Test
    @DisplayName("查单返回未支付:订单保持已关闭")
    void keepsClosedOrderWhenNotPaid() {
        Long orderId = closedOrderThatWasPrepaid();
        wxStub.setQueryTradeState("NOTPAY");

        tasks.reconcileClosedPaidOrders();

        assertThat(orderStatusOf(orderId)).isEqualTo(MallOrder.STATUS_CANCELLED);
    }

    @Test
    @DisplayName("没拉起过支付的订单不去查 —— 没拉起就不可能付过,查了是白跑")
    void skipsOrdersWithoutPrepayId() {
        Long orderId = closedOrderThatWasPrepaid();
        String outTradeNo = outTradeNoOf(orderId);
        inTenant(() -> {
            MallWxPayment payment = paymentRepository.findFirstByOrderIdOrderByIdDesc(orderId).orElseThrow();
            payment.setPrepayId(null);
            paymentRepository.save(payment);
            return null;
        });
        wxStub.setQueryTradeState("SUCCESS");

        tasks.reconcileClosedPaidOrders();

        assertThat(wxStub.requests())
                .noneMatch(request -> request.url().contains(outTradeNo));
        assertThat(orderStatusOf(orderId)).isEqualTo(MallOrder.STATUS_CANCELLED);
    }

    /** 造一笔"已关闭、已拉起过支付、支付流水仍是待支付"的订单(关单走真实入口,锁定与 cancel_time 都是真的)。 */
    private Long closedOrderThatWasPrepaid() {
        Long orderId = createOrder(customerId, addressId, 1).orderId();
        asClientRun(customerId, () -> orderService.cancel(orderId));
        inTenant(() -> {
            MallWxPayment payment = paymentRepository.findFirstByOrderIdOrderByIdDesc(orderId).orElseThrow();
            payment.setPrepayId("stub-prepay-1");
            paymentRepository.save(payment);
            return null;
        });
        return orderId;
    }

    private int[] skuSnapshot() {
        return inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            return new int[]{sku.getStock(), sku.getLockedStock()};
        });
    }

    private int orderStatusOf(Long orderId) {
        return inTenant(() -> orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    private int paymentStatusOf(Long orderId) {
        return inTenant(() -> paymentRepository.findFirstByOrderIdOrderByIdDesc(orderId)
                .orElseThrow().getPayStatus());
    }

    private String outTradeNoOf(Long orderId) {
        return inTenant(() -> paymentRepository.findFirstByOrderIdOrderByIdDesc(orderId)
                .orElseThrow().getOutTradeNo());
    }
}
