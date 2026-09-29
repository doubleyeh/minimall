package com.minimall.mall.infra.pay;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信下单与退款的报文组装验证。
 *
 * <p>HTTP 换成桩,所以既断言"发出去的是什么",又断言"各种响应怎么翻译" —— 全程不发真实请求。
 * 重点在 direct/partner 两种模式的字段名差异:它们错了不会有编译错误,只会在联调时失败。
 *
 * <p>密钥是测试内现造的,不是写死一份签名。
 */
class WxPayClientTest {

    private static final String API_V3_KEY = "01234567890123456789012345678901";
    private static final String TENANT_CODE = "qingning";
    private static final Long TENANT_ID = 1L;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("direct 模式:appid/mchid/openid,回调地址带租户编码,金额是分")
    void directOrderBody() throws Exception {
        StubHttpClient http = new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(
                200, "{\"prepay_id\":\"wx-prepay-1\"}"));
        KeyPair keyPair = keyPair();
        WxPayClient client = client(http, credentials(keyPair, false, null), properties("https://pay.test"));

        Optional<WxPayClient.PrepayResult> result = client.unifiedOrder(new WxPayOrderCommand(
                TENANT_ID, TENANT_CODE, "O1", new BigDecimal("60.00"), "openid-1", "商城订单 O1"));

        assertThat(result).isPresent();
        assertThat(http.url).isEqualTo("https://api.mch.weixin.qq.com/v3/pay/transactions/jsapi");

        JsonNode body = mapper.readTree(http.body);
        assertThat(body.get("appid").asText()).isEqualTo("wxappid");
        assertThat(body.get("mchid").asText()).isEqualTo("1900000001");
        assertThat(body.get("payer").get("openid").asText()).isEqualTo("openid-1");
        assertThat(body.get("out_trade_no").asText()).isEqualTo("O1");
        assertThat(body.get("notify_url").asText()).isEqualTo("https://pay.test/pay/callback/wx/" + TENANT_CODE);
        assertThat(body.get("amount").get("total").asInt()).isEqualTo(6000);
        assertThat(body.get("amount").get("currency").asText()).isEqualTo("CNY");
        assertThat(body.has("sp_mchid")).isFalse();

        assertThat(http.headers.get("Authorization")).startsWith("WECHATPAY2-SHA256-RSA2048 mchid=\"1900000001\"");
        assertThat(result.orElseThrow().prepayId()).isEqualTo("wx-prepay-1");
    }

    @Test
    @DisplayName("partner 模式:sp_appid/sp_mchid/sub_appid/sub_mchid,openid 走 sub_openid")
    void partnerOrderBody() throws Exception {
        StubHttpClient http = new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(
                200, "{\"prepay_id\":\"wx-prepay-2\"}"));
        KeyPair keyPair = keyPair();
        WxPayClient client = client(http, credentials(keyPair, true, "wxsubappid"), properties("https://pay.test"));

        assertThat(client.unifiedOrder(new WxPayOrderCommand(
                TENANT_ID, TENANT_CODE, "O2", new BigDecimal("12.34"), "openid-2", "商城订单 O2"))).isPresent();

        JsonNode body = mapper.readTree(http.body);
        assertThat(body.get("sp_appid").asText()).isEqualTo("wxappid");
        assertThat(body.get("sp_mchid").asText()).isEqualTo("1900000001");
        assertThat(body.get("sub_appid").asText()).isEqualTo("wxsubappid");
        assertThat(body.get("sub_mchid").asText()).isEqualTo("1234567890");
        assertThat(body.get("payer").get("sub_openid").asText()).isEqualTo("openid-2");
        assertThat(body.get("amount").get("total").asInt()).isEqualTo(1234);
        assertThat(body.has("appid")).as("partner 不能再用 appid 字段").isFalse();
    }

    @Test
    @DisplayName("partner 没配 sub_appid 时,openid 字段退化为 sp_openid")
    void partnerWithoutSubAppidUsesSpOpenid() throws Exception {
        StubHttpClient http = new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(
                200, "{\"prepay_id\":\"wx-prepay-3\"}"));
        WxPayClient client = client(http, credentials(keyPair(), true, null), properties("https://pay.test"));

        client.unifiedOrder(new WxPayOrderCommand(
                TENANT_ID, TENANT_CODE, "O3", BigDecimal.ONE, "openid-3", "商城订单 O3"));

        JsonNode body = mapper.readTree(http.body);
        assertThat(body.get("payer").get("sp_openid").asText()).isEqualTo("openid-3");
        assertThat(body.has("sub_appid")).isFalse();
    }

    @Test
    @DisplayName("paySign 的签名串是 appId\\n时间戳\\n随机串\\npackage\\n")
    void paySignIsVerifiable() throws Exception {
        StubHttpClient http = new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(
                200, "{\"prepay_id\":\"wx-prepay-4\"}"));
        KeyPair keyPair = keyPair();
        WxPayClient client = client(http, credentials(keyPair, false, null), properties("https://pay.test"));

        WxPayClient.PrepayResult.PayParams params = client.unifiedOrder(new WxPayOrderCommand(
                TENANT_ID, TENANT_CODE, "O4", BigDecimal.TEN, "openid-4", "商城订单 O4"))
                .orElseThrow().payParams();

        assertThat(params.packageValue()).isEqualTo("prepay_id=wx-prepay-4");
        assertThat(params.signType()).isEqualTo("RSA");
        String signString = "wxappid" + "\n" + params.timeStamp() + "\n" + params.nonceStr() + "\n"
                + params.packageValue() + "\n";
        assertThat(WxPayCrypto.verify(signString, params.paySign(), publicPem(keyPair))).isTrue();
    }

    @Test
    @DisplayName("回调地址前缀末尾带斜杠时不能拼出双斜杠")
    void notifyUrlTrimsTrailingSlash() throws Exception {
        StubHttpClient http = new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(
                200, "{\"prepay_id\":\"p\"}"));
        WxPayClient client = client(http, credentials(keyPair(), false, null), properties("https://pay.test/"));
        client.unifiedOrder(new WxPayOrderCommand(
                TENANT_ID, TENANT_CODE, "O5", BigDecimal.ONE, "openid", "商城订单 O5"));
        assertThat(http.body).contains("https://pay.test/pay/callback/wx/" + TENANT_CODE);
    }

    @Test
    @DisplayName("微信返回非 200 或缺 prepay_id,都返回空而不是抛异常")
    void failureBecomesEmpty() throws Exception {
        StubHttpClient bad = new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(403, "{\"code\":\"SIGN_ERROR\"}"));
        assertThat(client(bad, credentials(keyPair(), false, null), properties("https://pay.test"))
                .unifiedOrder(new WxPayOrderCommand(TENANT_ID, TENANT_CODE, "O6", BigDecimal.ONE, "o", "d")))
                .isEmpty();

        StubHttpClient noPrepay = new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(200, "{}"));
        assertThat(client(noPrepay, credentials(keyPair(), false, null), properties("https://pay.test"))
                .unifiedOrder(new WxPayOrderCommand(TENANT_ID, TENANT_CODE, "O7", BigDecimal.ONE, "o", "d")))
                .isEmpty();
    }

    @Test
    @DisplayName("退款:优先用 transaction_id,带原单金额,partner 带 sub_mchid")
    void refundBody() throws Exception {
        StubHttpClient http = new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(
                200, "{\"refund_id\":\"wx-refund-1\"}"));
        WxPayClient client = client(http, credentials(keyPair(), true, "wxsubappid"), properties("https://pay.test"));

        assertThat(client.refund(new WxPayRefundCommand(TENANT_ID, TENANT_CODE, "O1", "4200001",
                "R1", new BigDecimal("60.00"), new BigDecimal("120.00"), "售后退款")))
                .contains("wx-refund-1");

        assertThat(http.url).isEqualTo("https://api.mch.weixin.qq.com/v3/refund/domestic/refunds");
        JsonNode body = mapper.readTree(http.body);
        assertThat(body.get("transaction_id").asText()).isEqualTo("4200001");
        assertThat(body.has("out_trade_no")).as("有 transaction_id 就不该再带 out_trade_no").isFalse();
        assertThat(body.get("out_refund_no").asText()).isEqualTo("R1");
        assertThat(body.get("sub_mchid").asText()).isEqualTo("1234567890");
        assertThat(body.get("amount").get("refund").asInt()).isEqualTo(6000);
        assertThat(body.get("amount").get("total").asInt()).isEqualTo(12000);
        assertThat(body.get("notify_url").asText())
                .isEqualTo("https://pay.test/pay/callback/wx/" + TENANT_CODE + "/refund");
    }

    @Test
    @DisplayName("没配回调地址前缀、或租户没配支付,都报渠道未配置")
    void missingConfigIsBusinessError() throws Exception {
        assertThatThrownBy(() -> client(new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(200, "{}")),
                credentials(keyPair(), false, null), properties(null))
                .unifiedOrder(new WxPayOrderCommand(TENANT_ID, TENANT_CODE, "O8", BigDecimal.ONE, "o", "d")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PAY_CHANNEL_NOT_CONFIGURED);

        assertThatThrownBy(() -> client(new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(200, "{}")),
                null, properties("https://pay.test"))
                .unifiedOrder(new WxPayOrderCommand(TENANT_ID, TENANT_CODE, "O9", BigDecimal.ONE, "o", "d")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PAY_CHANNEL_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("查单:GET 到商户订单号 URL,带 mchid,签名串里 body 段为空")
    void queryOrderUsesGetWithMchid() throws Exception {
        StubHttpClient http = new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(
                200, "{\"trade_state\":\"SUCCESS\",\"transaction_id\":\"4200001\"}"));
        KeyPair keyPair = keyPair();
        WxPayClient client = client(http, credentials(keyPair, false, null), properties("https://pay.test"));

        assertThat(client.queryOrder(TENANT_ID, "O1")).contains(
                new WxPayClient.QueryResult("SUCCESS", "4200001"));

        assertThat(http.method).isEqualTo("GET");
        assertThat(http.url)
                .isEqualTo("https://api.mch.weixin.qq.com/v3/pay/transactions/out-trade-no/O1?mchid=1900000001");
        assertThat(http.body).as("查单没有请求体").isNull();
        assertThat(http.headers.get("Authorization")).startsWith("WECHATPAY2-SHA256-RSA2048 mchid=\"1900000001\"");
    }

    @Test
    @DisplayName("查单:非 200、或缺 trade_state,都返回空而不是抛异常")
    void queryOrderFailureBecomesEmpty() throws Exception {
        assertThat(client(new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(404, "{\"code\":\"ORDER_NOT_EXIST\"}")),
                credentials(keyPair(), false, null), properties("https://pay.test")).queryOrder(TENANT_ID, "O2"))
                .isEmpty();

        assertThat(client(new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(200, "{}")),
                credentials(keyPair(), false, null), properties("https://pay.test")).queryOrder(TENANT_ID, "O3"))
                .isEmpty();
    }

    @Test
    @DisplayName("查单:未支付也要回结果(调用方按 trade_state 判断,不能当成查询失败)")
    void queryOrderReturnsNotPayState() throws Exception {
        StubHttpClient http = new StubHttpClient(new WxPayHttpClient.WxPayHttpResult(
                200, "{\"trade_state\":\"NOTPAY\"}"));

        assertThat(client(http, credentials(keyPair(), false, null), properties("https://pay.test"))
                .queryOrder(TENANT_ID, "O4"))
                .contains(new WxPayClient.QueryResult("NOTPAY", ""));
    }

    private WxPayClient client(StubHttpClient http, WxPayCredentials credentials, WxPayProperties properties) {
        WxPayConfigProvider provider = new WxPayConfigProvider() {
            @Override
            public Optional<WxPayCredentials> byTenantId(Long tenantId) {
                return Optional.ofNullable(credentials);
            }

            @Override
            public void evict(Long tenantId) {
                // 桩不需要实现
            }
        };
        return new WxPayClient(provider, http, properties, new StubTenantLookup());
    }

    private WxPayProperties properties(String notifyBaseUrl) {
        return new WxPayProperties(notifyBaseUrl, null, null, null, null);
    }

    private WxPayCredentials credentials(KeyPair keyPair, boolean partner, String subAppId) {
        return new WxPayCredentials(TENANT_ID, partner ? "partner" : "direct",
                "1900000001", partner ? "1234567890" : null,
                "wxappid", "app-secret", subAppId, subAppId == null ? null : "sub-secret",
                "app", API_V3_KEY, "SER123", privatePem(keyPair), "PLAT123", publicPem(keyPair));
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String privatePem(KeyPair keyPair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    private String publicPem(KeyPair keyPair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    /** 回调验签用的租户查询桩;本测试只测出网报文,这里不需要真实数据。 */
    private static final class StubTenantLookup implements com.minimall.infra.tenant.TenantLookup {

        @Override
        public Optional<com.minimall.infra.tenant.TenantSnapshot> byCode(String tenantCode) {
            return Optional.empty();
        }

        @Override
        public Optional<com.minimall.infra.tenant.TenantSnapshot> byId(Long tenantId) {
            return Optional.empty();
        }

        @Override
        public void evict(com.minimall.infra.tenant.TenantSnapshot snapshot) {
            // 桩不需要实现
        }
    }

    /** 记录最后一次请求的桩。 */
    private static final class StubHttpClient implements WxPayHttpClient {

        private final WxPayHttpResult result;
        private String method;
        private String url;
        private Map<String, String> headers;
        private String body;

        StubHttpClient(WxPayHttpResult result) {
            this.result = result;
        }

        @Override
        public WxPayHttpResult post(String url, Map<String, String> headers, String body) {
            return record("POST", url, headers, body);
        }

        @Override
        public WxPayHttpResult get(String url, Map<String, String> headers) {
            return record("GET", url, headers, null);
        }

        private WxPayHttpResult record(String method, String url, Map<String, String> headers, String body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
            return result;
        }
    }
}
