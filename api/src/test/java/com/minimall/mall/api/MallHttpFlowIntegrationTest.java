package com.minimall.mall.api;

import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 小程序商城链路的**真 HTTP** 端到端用例(商城设计文档 3.1、3.3、3.8)。
 *
 * <p>为什么必须走真实 HTTP:整条链路上有两个东西只在 servlet 过滤链里成立 ——
 * <ul>
 *   <li><b>客户端身份</b>:{@code ClientAuthFilter} 负责解析 JWT、设置租户上下文、
 *       校验客户状态并重绑过滤器。直接调 service 等于跳过它,而 token 不生效、
 *       租户取自请求头这类问题恰恰是它引入的</li>
 *   <li><b>公开路径的边界</b>:哪些接口允许游客访问由白名单决定(商品浏览/领券列表),
 *       写错一个字符的后果是"该拦的没拦"或"不该拦的拦了",两者都只有打真实请求才能发现</li>
 * </ul>
 *
 * <p>用例依赖 dev profile 的 {@code wx-mock=true}:任意 code 都会被映射成模拟 openid,
 * 所以不需要真实微信环境就能跑完整条"登录 → 下单 → 支付 → 售后"。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class MallHttpFlowIntegrationTest {

    private static final long TENANT_ID = 1L;
    private static final String TENANT_CODE = "platform";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private MallGoodsRepository goodsRepository;
    @Autowired
    private MallSkuRepository skuRepository;
    @Autowired
    private MallOrderRepository orderRepository;

    private String token;
    private Long goodsId;
    private Long skuId;

    @BeforeEach
    void setUp() {
        inTenant(() -> {
            MallGoods goods = new MallGoods();
            goods.setCategoryId(0L);
            goods.setGoodsName("端到端测试商品");
            goods.setMainImage("https://example.com/e2e.png");
            goods.setSalePriceMin(new BigDecimal("60.00"));
            goods.setSalePriceMax(new BigDecimal("60.00"));
            goods.setTotalStock(5);
            goods.setSaleCount(0);
            goods.setStatus(1);
            goods.setSortOrder(0);
            goodsId = goodsRepository.save(goods).getId();

            MallSku sku = new MallSku();
            sku.setGoodsId(goodsId);
            sku.setSkuCode("E2E-" + System.nanoTime());
            sku.setSkuName("默认规格");
            sku.setPrice(new BigDecimal("60.00"));
            sku.setStock(5);
            sku.setLockedStock(0);
            sku.setStatus(1);
            skuId = skuRepository.save(sku).getId();
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        inTenant(() -> {
            orderRepository.findAll().stream()
                    .filter(order -> order.getGoodsAmount() != null
                            && order.getGoodsAmount().compareTo(new BigDecimal("60.00")) == 0)
                    .forEach(orderRepository::delete);
            skuRepository.findById(skuId).ifPresent(skuRepository::delete);
            goodsRepository.findById(goodsId).ifPresent(goodsRepository::delete);
            return null;
        });
    }

    @Test
    @DisplayName("用例1:商品浏览与领券列表允许游客访问,其余 /mall/api/** 一律 401")
    void publicPathsAreAccessibleWithoutToken() {
        // 公开:小程序里"先逛后登录"是常态(ClientAuthFilter 的公开清单)
        assertThat(get("/mall/api/categories", null).status()).isEqualTo(200);
        assertThat(get("/mall/api/goods?pageNo=1&pageSize=5", null).status()).isEqualTo(200);
        assertThat(get("/mall/api/coupons/claimable", null).status()).isEqualTo(200);
        assertThat(get("/mall/api/goods/" + goodsId, null).status()).isEqualTo(200);

        // 非公开:客户自己的数据必须带令牌
        assertThat(get("/mall/api/cart", null).status())
                .as("购物车没有令牌必须 401,不能返回空列表 —— 那会把没登录伪装成没数据")
                .isEqualTo(401);
        assertThat(get("/mall/api/profile", null).status()).isEqualTo(401);
        assertThat(get("/mall/api/orders?pageNo=1&pageSize=5", null).status()).isEqualTo(401);
        assertThat(get("/mall/api/coupons/mine", null).status()).isEqualTo(401);

        // 伪造令牌同样被拒(签名校验不通过)
        assertThat(get("/mall/api/profile", "fake.token.value").status()).isEqualTo(401);
    }

    @Test
    @DisplayName("用例2:微信登录(模拟)拿到 30 天令牌,令牌可访问需要登录的接口")
    void wxLoginIssuesUsableToken() {
        String loginBody = "{\"code\":\"e2e-code-" + System.nanoTime() + "\"}";
        Response login = post("/mall/api/auth/wx-login", loginBody, null);
        assertThat(login.status()).isEqualTo(200);
        assertThat(login.code()).isZero();

        String issued = login.text("token");
        assertThat(issued).isNotBlank();
        // 有效期 30 天(3.1)
        assertThat(login.text("expiresIn")).isEqualTo(String.valueOf(30 * 24 * 3600));

        // 登录返回的令牌能直接用于业务接口
        assertThat(get("/mall/api/profile", issued).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("用例3:登录后可用令牌更新昵称,读回时已生效")
    void profileUpdateTakesEffect() {
        login();
        String nickname = "端到端" + System.nanoTime() % 100000;
        assertThat(put("/mall/api/profile", "{\"nickname\":\"" + nickname + "\"}", token).status()).isEqualTo(200);
        assertThat(get("/mall/api/profile", token).body()).contains(nickname);
    }

    @Test
    @DisplayName("用例4:加购 → 下单 → 支付回调 → 订单进入待发货(核心链路)")
    void fullOrderFlowOverHttp() {
        login();

        // 1) 建收货地址(第一条会自动成为默认地址)
        Response address = post("/mall/api/addresses",
                "{\"receiverName\":\"端到端\",\"receiverPhone\":\"13900000000\",\"province\":\"广东省\","
                        + "\"city\":\"深圳市\",\"district\":\"南山区\",\"detailAddress\":\"测试路 2 号\"}", token);
        assertThat(address.status()).isEqualTo(200);
        // 新增接口直接返回 ID 作为 data(不是对象),用 text 取即可
        Long addressId = Long.valueOf(address.text("data"));

        // 2) 加购
        assertThat(post("/mall/api/cart", "{\"skuId\":" + skuId + ",\"quantity\":2}", token).status()).isEqualTo(200);
        assertThat(get("/mall/api/cart", token).body()).contains("端到端测试商品");

        // 3) 下单(不传 items,由后端取购物车已勾选条目)
        Response order = post("/mall/api/orders",
                "{\"addressId\":" + addressId + ",\"remark\":\"端到端下单\"}", token);
        assertThat(order.status()).isEqualTo(200);
        assertThat(order.code()).isZero();
        long orderId = Long.parseLong(order.text("orderId"));
        String orderNo = order.text("orderNo");
        assertThat(orderNo).hasSize(16);
        // 60 × 2,无运费无优惠
        assertThat(order.text("payAmount")).isEqualTo("120.00");

        // 4) 拉起支付(dev 下走模拟渠道,会返回 prepay_id 对应的参数)
        Response prepay = post("/mall/api/orders/" + orderId + "/prepay", null, token);
        assertThat(prepay.status()).isEqualTo(200);

        // 5) 支付回调(真实环境由微信服务器发起,这里手动触发以走通后续流程)
        Response callback = post("/pay/callback/wx",
                "{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"e2e-" + System.nanoTime()
                        + "\",\"amount\":120.00,\"success\":true,\"rawBody\":\"{}\"}", null);
        assertThat(callback.status()).isEqualTo(200);

        // 6) 订单进入待发货,库存从"锁定"变成"实扣"
        Response detail = get("/mall/api/orders/" + orderId, token);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.text("status")).isEqualTo("2");
        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            assertThat(sku.getStock()).isEqualTo(3);
            assertThat(sku.getLockedStock()).isZero();
            return null;
        });
    }

    @Test
    @DisplayName("用例5:申请售后走 HTTP 成立,列表里能看到自己的售后单")
    void afterSaleApplicationOverHttp() {
        login();
        Response address = post("/mall/api/addresses",
                "{\"receiverName\":\"端到端\",\"receiverPhone\":\"13900000001\",\"province\":\"广东省\","
                        + "\"city\":\"深圳市\",\"district\":\"南山区\",\"detailAddress\":\"测试路 3 号\"}", token);
        Long addressId = Long.valueOf(address.text("data"));

        Response order = post("/mall/api/orders",
                "{\"items\":[{\"skuId\":" + skuId + ",\"quantity\":1}],\"addressId\":" + addressId + "}", token);
        long orderId = Long.parseLong(order.text("orderId"));
        String orderNo = order.text("orderNo");
        post("/pay/callback/wx", "{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"e2e-as-"
                + System.nanoTime() + "\",\"amount\":60.00,\"success\":true,\"rawBody\":\"{}\"}", null);

        // 待发货(=已支付)时才能申请仅退款(3.9 的入口限制)
        String orderDetail = get("/mall/api/orders/" + orderId, token).body();
        Matcher matcher = Pattern.compile("\"items\":\\[\\{\"id\":(\\d+)").matcher(orderDetail);
        assertThat(matcher.find()).as("订单详情里应当能取到明细 ID").isTrue();
        long orderItemId = Long.parseLong(matcher.group(1));

        Response apply = post("/mall/api/after-sales",
                "{\"orderItemId\":" + orderItemId + ",\"afterSaleType\":1,\"applyReason\":\"不想要了\","
                        + "\"refundAmount\":60.00}", token);
        assertThat(apply.status()).isEqualTo(200);
        assertThat(apply.code()).isZero();

        Response list = get("/mall/api/after-sales", token);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).contains("待商家处理");
        // 订单进入"售后中"(状态联动,3.9)
        assertThat(get("/mall/api/orders/" + orderId, token).text("status")).isEqualTo("6");
    }

    // ---------------------------------------------------------------- HTTP 辅助

    private void login() {
        Response login = post("/mall/api/auth/wx-login",
                "{\"code\":\"e2e-" + System.nanoTime() + "\"}", null);
        assertThat(login.status()).isEqualTo(200);
        token = login.text("token");
        assertThat(token).isNotBlank();
    }

    private Response get(String path, String bearer) {
        return send(HttpRequest.newBuilder().uri(uri(path)).GET(), bearer);
    }

    private Response post(String path, String body, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri(path))
                .header("Content-Type", "application/json");
        builder.POST(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return send(builder, bearer);
    }

    private Response put(String path, String body, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri(path))
                .header("Content-Type", "application/json");
        builder.PUT(body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return send(builder, bearer);
    }

    private Response send(HttpRequest.Builder builder, String bearer) {
        // 每个请求都带租户编码:微信 openid 按小程序发放,后端靠它确定是哪个商家
        builder.header("X-Tenant-Code", TENANT_CODE);
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        try {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (Exception ex) {
            throw new IllegalStateException("HTTP 请求失败: " + builder, ex);
        }
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    /** 响应包装:用正则从紧凑 JSON 里取值,避免依赖具体的 JSON 库(Boot 4 换了 Jackson 3)。 */
    private record Response(int status, String body) {

        boolean isOk() {
            return status == 200;
        }

        /** 业务码(code 字段)。 */
        int code() {
            Matcher matcher = Pattern.compile("\"code\":(\\d+)").matcher(body);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        }

        /** 取指定字段的**第一个**匹配值(字符串或数字)。 */
        String text(String field) {
            Matcher matcher = Pattern.compile("\"" + field + "\":\"?([^\",}]+)\"?").matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        }

        /** 取 data 段(业务数据)。 */
        String data() {
            Matcher matcher = Pattern.compile("\"data\":").matcher(body);
            if (!matcher.find()) {
                return "";
            }
            return body.substring(matcher.end());
        }
    }

    private <T> T inTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, false, () -> {
            AuditContext.bind(new AuditContext(TENANT_ID, 1L, "127.0.0.1", "mall-http-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }
}
