package com.minimall.mall.api;

import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallWxPayment;
import com.minimall.mall.domain.MallWxRefund;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallWxPaymentRepository;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
import com.minimall.mall.service.support.WxPayCallbackFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信回调的 HTTP 契约验证(商城设计文档 3.8)。
 *
 * <p>两个必须钉死的点:
 * <ol>
 *   <li>成功必须返回微信认的 {@code {"code":"SUCCESS"}} —— 返回项目自己的 {@code {code:0}} 等于告诉
 *       微信"失败了",它会一直重推</li>
 *   <li>验签失败/序列号不匹配必须返回**非 2xx**:走全局异常处理器会被翻译成 200,微信同样会当成成功</li>
 * </ol>
 *
 * <p>报文是夹具造的真签名 + 真密文,不是明文 DTO。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WxPayCallbackHttpIntegrationTest {

    private static final long TENANT_ID = 1L;
    private static final String TENANT_CODE = "platform";
    private static final BigDecimal AMOUNT = new BigDecimal("60.00");

    @LocalServerPort
    private int port;

    @Autowired
    private WxPayCallbackFixture callback;
    @Autowired
    private MallOrderRepository orderRepository;
    @Autowired
    private MallWxPaymentRepository paymentRepository;
    @Autowired
    private MallWxRefundRepository refundRepository;

    private String outTradeNo;
    private Long orderId;

    @BeforeEach
    void prepare() {
        callback.ensureConfig(TENANT_CODE);
        outTradeNo = "cbk" + System.nanoTime();
        orderId = TenantContext.callAsTenant(TENANT_ID, false, () -> {
            MallOrder order = new MallOrder();
            order.setOrderNo(outTradeNo);
            order.setCustomerId(1L);
            order.setStatus(MallOrder.STATUS_PENDING_PAY);
            order.setGoodsAmount(AMOUNT);
            order.setFreightAmount(BigDecimal.ZERO);
            order.setCouponDiscountAmount(BigDecimal.ZERO);
            order.setPromotionDiscountAmount(BigDecimal.ZERO);
            order.setPayAmount(AMOUNT);
            order.setReceiverName("测试");
            order.setReceiverPhone("13900000000");
            order.setReceiverAddress("测试地址");
            MallOrder saved = orderRepository.save(order);

            MallWxPayment payment = new MallWxPayment();
            payment.setOrderId(saved.getId());
            payment.setOutTradeNo(outTradeNo);
            payment.setPayAmount(AMOUNT);
            payment.setPayStatus(MallWxPayment.PAY_STATUS_PENDING);
            paymentRepository.save(payment);
            return saved.getId();
        });
    }

    @Test
    @DisplayName("支付回调:返回微信标准的 SUCCESS,订单置为待发货")
    void paymentCallbackReturnsSuccess() throws Exception {
        WxPayCallbackFixture.Payload payload = callback.payload("TRANSACTION.SUCCESS", Map.of(
                "out_trade_no", outTradeNo,
                "transaction_id", "cbk-txn-" + System.nanoTime(),
                "amount", Map.of("total", WxPayCallbackFixture.toCents(AMOUNT), "currency", "CNY")),
                "transaction");

        HttpResponse<String> response = post("/pay/callback/wx/" + TENANT_CODE, payload, payload.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"code\":\"SUCCESS\"");
        assertThat(statusOf(orderId)).isEqualTo(MallOrder.STATUS_PENDING_SHIP);
    }

    @Test
    @DisplayName("重复推送要幂等,且仍返回 SUCCESS")
    void duplicateCallbackIsIdempotent() throws Exception {
        WxPayCallbackFixture.Payload payload = callback.payload("TRANSACTION.SUCCESS", Map.of(
                "out_trade_no", outTradeNo,
                "transaction_id", "cbk-dup-" + System.nanoTime(),
                "amount", Map.of("total", WxPayCallbackFixture.toCents(AMOUNT), "currency", "CNY")),
                "transaction");

        assertThat(post("/pay/callback/wx/" + TENANT_CODE, payload, payload.body()).statusCode()).isEqualTo(200);
        assertThat(post("/pay/callback/wx/" + TENANT_CODE, payload, payload.body()).statusCode()).isEqualTo(200);
        assertThat(statusOf(orderId)).isEqualTo(MallOrder.STATUS_PENDING_SHIP);
    }

    @Test
    @DisplayName("改一个字节(签名不变)必须返回 401 + FAIL,且订单不动")
    void tamperedBodyIsRejected() throws Exception {
        WxPayCallbackFixture.Payload payload = callback.payload("TRANSACTION.SUCCESS", Map.of(
                "out_trade_no", outTradeNo,
                "transaction_id", "cbk-bad-" + System.nanoTime(),
                "amount", Map.of("total", WxPayCallbackFixture.toCents(AMOUNT), "currency", "CNY")),
                "transaction");

        HttpResponse<String> response = post("/pay/callback/wx/" + TENANT_CODE, payload, payload.body() + " ");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"code\":\"FAIL\"");
        assertThat(statusOf(orderId)).isEqualTo(MallOrder.STATUS_PENDING_PAY);
    }

    @Test
    @DisplayName("序列号不匹配必须返回 401")
    void wrongSerialIsRejected() throws Exception {
        WxPayCallbackFixture.Payload payload = callback.payload("TRANSACTION.SUCCESS", Map.of(
                "out_trade_no", outTradeNo,
                "transaction_id", "cbk-ser-" + System.nanoTime(),
                "amount", Map.of("total", WxPayCallbackFixture.toCents(AMOUNT), "currency", "CNY")),
                "transaction");

        assertThat(post("/pay/callback/wx/" + TENANT_CODE, payload.serial() + "x", payload, payload.body())
                .statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("金额不符必须返回 400,订单不推进")
    void amountMismatchIsRejected() throws Exception {
        WxPayCallbackFixture.Payload payload = callback.payload("TRANSACTION.SUCCESS", Map.of(
                "out_trade_no", outTradeNo,
                "transaction_id", "cbk-amt-" + System.nanoTime(),
                "amount", Map.of("total", 1, "currency", "CNY")), "transaction");

        assertThat(post("/pay/callback/wx/" + TENANT_CODE, payload, payload.body()).statusCode()).isEqualTo(400);
        assertThat(statusOf(orderId)).isEqualTo(MallOrder.STATUS_PENDING_PAY);
    }

    @Test
    @DisplayName("未知租户返回 404")
    void unknownTenantIsRejected() throws Exception {
        WxPayCallbackFixture.Payload payload = callback.payload("TRANSACTION.SUCCESS", Map.of(
                "out_trade_no", outTradeNo,
                "amount", Map.of("total", WxPayCallbackFixture.toCents(AMOUNT), "currency", "CNY")),
                "transaction");
        assertThat(post("/pay/callback/wx/绝不存在的租户", payload, payload.body()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("退款回调:成功置为已退款,失败置为退款失败")
    void refundCallbackUpdatesStatus() throws Exception {
        String outRefundNo = "cbkr" + System.nanoTime();
        Long refundId = TenantContext.callAsTenant(TENANT_ID, false, () -> {
            MallWxRefund refund = new MallWxRefund();
            refund.setOrderId(orderId);
            refund.setAfterSaleId(0L);
            refund.setOutRefundNo(outRefundNo);
            refund.setRefundAmount(AMOUNT);
            refund.setRefundStatus(MallWxRefund.REFUND_STATUS_APPLYING);
            return refundRepository.save(refund).getId();
        });

        assertThat(post("/pay/callback/wx/" + TENANT_CODE + "/refund",
                callback.payload("REFUND.SUCCESS", Map.of(
                        "out_refund_no", outRefundNo,
                        "refund_id", "cbk-refund-" + System.nanoTime(),
                        "refund_status", "SUCCESS"), "refund"), null).statusCode()).isEqualTo(200);
        assertThat(refundStatusOf(refundId)).isEqualTo(MallWxRefund.REFUND_STATUS_SUCCESS);

        String failedNo = "cbkf" + System.nanoTime();
        Long failedId = TenantContext.callAsTenant(TENANT_ID, false, () -> {
            MallWxRefund refund = new MallWxRefund();
            refund.setOrderId(orderId);
            refund.setAfterSaleId(0L);
            refund.setOutRefundNo(failedNo);
            refund.setRefundAmount(AMOUNT);
            refund.setRefundStatus(MallWxRefund.REFUND_STATUS_APPLYING);
            return refundRepository.save(refund).getId();
        });
        assertThat(post("/pay/callback/wx/" + TENANT_CODE + "/refund",
                callback.payload("REFUND.CLOSED", Map.of(
                        "out_refund_no", failedNo,
                        "refund_id", "cbk-refund-f-" + System.nanoTime(),
                        "refund_status", "CLOSED"), "refund"), null).statusCode()).isEqualTo(200);
        assertThat(refundStatusOf(failedId)).isEqualTo(MallWxRefund.REFUND_STATUS_FAILED);
    }

    private HttpResponse<String> post(String path, WxPayCallbackFixture.Payload payload, String body)
            throws Exception {
        return post(path, payload.serial(), payload, body);
    }

    private HttpResponse<String> post(String path, String serial, WxPayCallbackFixture.Payload payload,
                                      String body) throws Exception {
        String actualBody = body == null ? payload.body() : body;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("Wechatpay-Timestamp", payload.timestamp())
                .header("Wechatpay-Nonce", payload.nonce())
                .header("Wechatpay-Signature", payload.signature())
                .header("Wechatpay-Serial", serial)
                .POST(HttpRequest.BodyPublishers.ofString(actualBody, StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private int statusOf(Long id) {
        return TenantContext.callAsTenant(TENANT_ID, false,
                () -> orderRepository.findById(id).orElseThrow().getStatus());
    }

    private int refundStatusOf(Long id) {
        return TenantContext.callAsTenant(TENANT_ID, false,
                () -> refundRepository.findById(id).orElseThrow().getRefundStatus());
    }
}
