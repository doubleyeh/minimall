package com.minimall.mall.api;

import com.minimall.common.ErrorCode;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.api.dto.UserSaveRequest;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.repository.SysUserRepository;
import com.minimall.sys.service.UserService;
import com.minimall.mall.api.dto.CouponSaveRequest;
import com.minimall.mall.domain.MallCoupon;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.service.AfterSaleService;
import com.minimall.mall.service.CouponService;
import com.minimall.mall.service.OrderAdminService;
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
import java.time.LocalDateTime;
import java.util.List;
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
@ActiveProfiles("test")
class MallHttpFlowIntegrationTest {

    private static final long TENANT_ID = 1L;
    private static final String TENANT_CODE = "platform";
    private static final String ADMIN_PASSWORD = "E2eAdmin@123456";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private MallGoodsRepository goodsRepository;
    @Autowired
    private MallSkuRepository skuRepository;
    @Autowired
    private MallOrderRepository orderRepository;
    /** 以下三个服务只用来**造夹具**:商家侧发货/审核、可领取的券。它们的 HTTP 通路另有用例覆盖。 */
    @Autowired
    private OrderAdminService orderAdminService;
    @Autowired
    private AfterSaleService afterSaleService;
    @Autowired
    private CouponService couponService;

    private String token;
    private Long goodsId;
    private Long skuId;
    /** 平台超管令牌:/mall/admin/** 与 /system/** 用它(权限码由 4.10 短路;这条链路在权限矩阵用例里验证过)。 */
    private String adminToken;
    private String adminUsername;

    @Autowired
    private SysUserRepository sysUserRepository;
    @Autowired
    private UserService sysUserService;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        // 登录按 IP 限流(10 次/分钟):本类每个用例要登录两次(客户 + 超管),
        // 不清计数器的话跑到后面会莫名其妙拿到 429
        redis.delete(redis.keys("*127.0.0.1*"));

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

        // 造一个平台超管账号:商家侧接口要它。is_super 只能靠改库设置(4.10:接口既不接受也不暴露)
        adminUsername = "e2eadmin" + System.nanoTime();
        inTenant(() -> {
            sysUserService.create(new UserSaveRequest(adminUsername, ADMIN_PASSWORD, "端到端超管",
                    null, null, List.of(), 1));
            SysUser superUser = sysUserRepository.findByTenantIdAndUsername(TENANT_ID, adminUsername).orElseThrow();
            superUser.setIsSuper(1);
            superUser.setMustChangePassword(0);
            sysUserRepository.save(superUser);
            return null;
        });
        adminToken = loginAdmin();
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

        // 评价列表必须游客可读(商品页要展示评价),但**发表评价不能**:
        // 白名单里放的是 /mall/api/reviews/goods/**,不是 /mall/api/reviews/** ——
        // 多一个斜杠就会把"提交评价"也开放出去。这条断言就是防止那次误改
        assertThat(get("/mall/api/reviews/goods/" + goodsId, null).status()).isEqualTo(200);
        assertThat(post("/mall/api/reviews", "{\"orderItemId\":1,\"rating\":5}", null).status())
                .as("发表评价需要登录:下单过才能评价")
                .isEqualTo(401);
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
        Matcher matcher = Pattern.compile("\"items\":\\[\\{\"id\":\"?(\\d+)\"?").matcher(orderDetail);
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

    // ---------------------------------------------------------------- 商家侧写接口

    @Test
    @DisplayName("商家订单:待发货可发货,待付款可商家取消")
    void merchantOrderShipAndCancel() {
        login();
        Long addressId = createAddress("e2e商家订单", "13900000031");

        // ① 已支付(待发货)→ 发货 → 买家侧看到"待收货"
        long[] paid = createPaidOrder(addressId, 1);
        Response ship = post("/mall/admin/orders/" + paid[0] + "/ship",
                "{\"logisticsCompany\":\"顺丰速运\",\"logisticsNo\":\"SF" + System.nanoTime() + "\"}", adminToken);
        assertThat(ship.status()).as("发货失败:%s", ship.body()).isEqualTo(200);
        assertThat(ship.code()).isZero();
        assertThat(get("/mall/api/orders/" + paid[0], token).text("status"))
                .as("发货后买家侧要变成待收货").isEqualTo("3");

        // ② 待付款的订单不能由商家取消:该状态归买家自己取消或超时任务处理。
        //    这条守卫写错会让商家替买家把还没付钱的订单关掉
        long unpaid = createOrderOnly(addressId, 1);
        Response cancelUnpaid = post("/mall/admin/orders/" + unpaid + "/cancel", "{\"reason\":\"缺货\"}", adminToken);
        assertThat(cancelUnpaid.code())
                .as("只有待发货的订单可以由商家取消,响应=%s", cancelUnpaid.body())
                .isNotZero();

        // ③ 已支付(待发货)可以由商家取消,且已实扣的库存要放回去
        long[] second = createPaidOrder(addressId, 1);
        Response cancelPaid = post("/mall/admin/orders/" + second[0] + "/cancel", "{\"reason\":\"缺货\"}", adminToken);
        assertThat(cancelPaid.status()).as("商家取消失败:%s", cancelPaid.body()).isEqualTo(200);
        assertThat(cancelPaid.code()).isZero();
        assertThat(get("/mall/api/orders/" + second[0], token).text("status"))
                .as("取消后不再是待发货").isNotEqualTo("2");
    }

    @Test
    @DisplayName("商家售后:同意 / 拒绝 / 确认收货 / 拒绝收货 / 仲裁五个动作")
    void merchantAfterSaleActions() {
        login();
        Long addressId = createAddress("e2e商家售后", "13900000032");

        // ① 仅退款 → 商家拒绝 → 买家申请客服介入 → 商家仲裁通过
        long[] first = createPaidOrder(addressId, 1);
        Long as1 = applyAfterSale(first[1], 1);
        assertThat(post("/mall/admin/after-sales/" + as1 + "/reject", "{\"reason\":\"不符合仅退款条件\"}", adminToken)
                .code()).as("拒绝售后").isZero();
        assertThat(post("/mall/api/after-sales/" + as1 + "/arbitration", null, token).code()).isZero();
        assertThat(post("/mall/admin/after-sales/" + as1 + "/arbitrate",
                "{\"pass\":true,\"remark\":\"支持买家诉求\"}", adminToken).code()).as("仲裁").isZero();

        // ② 退货退款 → 商家同意 → 买家寄回 → 商家确认收货
        long[] second = createPaidOrder(addressId, 1);
        Long as2 = applyAfterSale(second[1], 2);
        assertThat(post("/mall/admin/after-sales/" + as2 + "/approve", "{\"refundAmount\":60.00}", adminToken)
                .code()).as("同意售后").isZero();
        assertThat(post("/mall/api/after-sales/" + as2 + "/return-logistics",
                "{\"company\":\"顺丰速运\",\"no\":\"SF-A\"}", token).code()).isZero();
        Response confirm = post("/mall/admin/after-sales/" + as2 + "/confirm-receive",
                "{\"logisticsCompany\":\"顺丰速运\",\"logisticsNo\":\"SF-A\"}", adminToken);
        assertThat(confirm.status()).as("确认收货失败:%s", confirm.body()).isEqualTo(200);
        assertThat(confirm.code()).as("确认收货").isZero();
        assertThat(get("/mall/api/after-sales/" + as2, token).body()).contains("售后完成");

        // ③ 退货退款 → 同意 → 寄回 → 商家拒绝收货(买家据此可申请客服介入)
        long[] third = createPaidOrder(addressId, 1);
        Long as3 = applyAfterSale(third[1], 2);
        assertThat(post("/mall/admin/after-sales/" + as3 + "/approve", "{\"refundAmount\":60.00}", adminToken)
                .code()).isZero();
        assertThat(post("/mall/api/after-sales/" + as3 + "/return-logistics",
                "{\"company\":\"顺丰速运\",\"no\":\"SF-B\"}", token).code()).isZero();
        assertThat(post("/mall/admin/after-sales/" + as3 + "/reject-receive",
                "{\"reason\":\"商品有损坏\"}", adminToken).code()).as("拒绝收货").isZero();
    }

    /**
     * 商家订单:发货与取消都只认"待发货"这一个前置状态。
     *
     * <p>为什么单独钉反向:上面那条用例走的是正向,而**状态机写漏一条守卫的后果是钱货两空** ——
     * 未付款就发货等于白送一件货,已收货还能取消等于货在买家手里却把钱退回去。
     * 这类守卫只在真实 HTTP 路径上才成立(服务层用例的事务边界不同,抛出的异常类型也不一样,
     * 见 defect_review 第 2 条),所以断言的是业务码本身而不只是"非 0"。
     */
    @Test
    @DisplayName("商家订单:未付款不能发货、已发货不能重复发货、已收货不能取消、物流单号为空不能发货")
    void merchantOrderRejectsIllegalTransitions() {
        login();
        Long addressId = createAddress("e2e订单越界", "13900000034");

        // ① 未付款不能发货:钱没收到、库存也没实扣
        long unpaid = createOrderOnly(addressId, 1);
        Response shipUnpaid = post("/mall/admin/orders/" + unpaid + "/ship",
                "{\"logisticsCompany\":\"顺丰速运\",\"logisticsNo\":\"SF" + System.nanoTime() + "\"}", adminToken);
        assertThat(shipUnpaid.code())
                .as("未付款不能发货,响应=%s", shipUnpaid.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        // ② 已发货不能重复发货:同一笔订单发两次,买家会收到两件货
        long[] paid = createPaidOrder(addressId, 1);
        String shipBody = "{\"logisticsCompany\":\"顺丰速运\",\"logisticsNo\":\"SF" + System.nanoTime() + "\"}";
        assertThat(post("/mall/admin/orders/" + paid[0] + "/ship", shipBody, adminToken).code())
                .as("首次发货应当成功").isZero();
        Response reship = post("/mall/admin/orders/" + paid[0] + "/ship", shipBody, adminToken);
        assertThat(reship.code())
                .as("已发货不能重复发货,响应=%s", reship.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        // ③ 已收货不能由商家取消:货已经在买家手里
        long[] received = createPaidOrder(addressId, 1);
        inTenant(() -> {
            orderAdminService.ship(received[0], "顺丰速运", "SF" + System.nanoTime());
            return null;
        });
        assertThat(post("/mall/api/orders/" + received[0] + "/receive", null, token).code())
                .as("买家确认收货").isZero();
        Response cancelReceived = post("/mall/admin/orders/" + received[0] + "/cancel",
                "{\"reason\":\"缺货\"}", adminToken);
        assertThat(cancelReceived.code())
                .as("已收货的订单不能由商家取消,响应=%s", cancelReceived.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        // ④ 物流单号为空也要挡在发货之前:单号缺失的订单买家永远查不到物流
        long[] blank = createPaidOrder(addressId, 1);
        Response blankShip = post("/mall/admin/orders/" + blank[0] + "/ship",
                "{\"logisticsCompany\":\"顺丰速运\",\"logisticsNo\":\"\"}", adminToken);
        assertThat(blankShip.code())
                .as("物流单号为空不能发货,响应=%s", blankShip.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
    }

    /**
     * 售后申请侧的边界。
     *
     * <p>"已完成订单也能申请退货退款"是 3.9 明确允许的 —— 买家收货后才发现质量问题同样要能退,
     * 所以那条状态守卫不能把已完成排除掉。另一条是**同一明细不能有两个进行中的售后**:
     * 放行的话同一件货会被退两次钱。
     */
    @Test
    @DisplayName("售后申请:同一明细不能重复申请、已完成订单可申请、空白单号按无筛选、图片夹空项被跳过")
    void afterSaleApplyBoundaries() {
        login();
        Long addressId = createAddress("e2e售后申请", "13900000051");

        // ① 同一订单明细再申请一次:这里断言的是**状态守卫先命中**。
        //    第一次申请会把订单置为"售后中",而 apply 的状态守卫不接受售后中,
        //    所以"该明细已有进行中的售后"(countActiveByOrderItemId)那条守卫从 HTTP 根本走不到 ——
        //    它被前面这条挡住了。把现状钉住,免得以后有人以为那条守卫在用
        long[] first = createPaidOrder(addressId, 1);
        Long firstAfterSale = applyAfterSale(first[1], 1);
        Response duplicated = post("/mall/api/after-sales",
                "{\"orderItemId\":" + first[1] + ",\"afterSaleType\":1,\"applyReason\":\"再申请一次\","
                        + "\"refundAmount\":60.00}", token);
        assertThat(duplicated.code())
                .as("订单已在售后中,再申请被状态守卫挡下,响应=%s", duplicated.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(firstAfterSale).isNotNull();

        // ② 已完成(买家已收货)的订单要能申请退货退款:这是 3.9 允许的场景
        long[] received = createPaidOrder(addressId, 1);
        inTenant(() -> {
            orderAdminService.ship(received[0], "顺丰速运", "SF" + System.nanoTime());
            return null;
        });
        assertThat(post("/mall/api/orders/" + received[0] + "/receive", null, token).code())
                .as("买家确认收货").isZero();
        Long receivedAfterSale = applyAfterSale(received[1], 2);
        assertThat(receivedAfterSale)
                .as("已完成订单的退货退款申请应当成立(收货后才发现质量问题也要能退)")
                .isNotNull();

        // 商家拒绝 → 订单**仍保持"售后中"**:被拒的售后单还能申请客服介入,不算终态。
        // 这条容易写错 —— 以为"商家拒了就结束了",但订单要等仲裁或买家撤销才回退
        assertThat(post("/mall/admin/after-sales/" + receivedAfterSale + "/reject", "{}", adminToken).code())
                .as("拒绝这个售后申请").isZero();
        assertThat(get("/mall/api/orders/" + received[0], token).text("status"))
                .as("被拒的售后单还能申请客服介入,所以订单此时仍应是售后中")
                .isEqualTo("6");

        // ③ 申请时带的图片数组里夹了 null 与空白项:跳过而不是建空图记录
        long[] third = createPaidOrder(addressId, 1);
        Response applied = post("/mall/api/after-sales",
                "{\"orderItemId\":" + third[1] + ",\"afterSaleType\":1,\"applyReason\":\"带图申请\","
                        + "\"refundAmount\":60.00,\"images\":[null,\"  \",\"https://example.com/as.png\"]}", token);
        assertThat(applied.status()).as("带图申请失败:%s", applied.body()).isEqualTo(200);
        assertThat(applied.code()).as("带图申请失败:%s", applied.body()).isZero();

        // ④ 售后列表里全空白的单号要按"没有这个筛选"处理,与不带该参数的结果一致
        String withoutFilter = get("/mall/admin/after-sales?pageSize=1", adminToken).text("total");
        String blankFilter = get("/mall/admin/after-sales?afterSaleNo=%20%20&pageSize=1", adminToken).text("total");
        assertThat(withoutFilter).as("列表要能取到总数").isNotNull();
        assertThat(blankFilter)
                .as("全空白的单号不该过滤掉任何数据(不带筛选时 total=%s)", withoutFilter)
                .isEqualTo(withoutFilter);
    }

    /**
     * 双明细订单的部分退款:一件退完**不代表整单退掉**(3.9)。
     *
     * <p>订单状态的回落规则在这里才真正被验到 —— 只要还有明细没退完,就不能把订单判成"已取消"。
     * 单明细订单永远走"整单退完 → 已取消"那条路,所以这条规则在之前的用例里从没被触发过。
     */
    @Test
    @DisplayName("售后:双明细订单只退一件时,订单回到已完成而不是被整单取消")
    void partialRefundKeepsOrderAlive() {
        login();
        Long addressId = createAddress("e2e部分退款", "13900000053");
        Long secondSkuId = createExtraSku();

        Response order = post("/mall/api/orders",
                "{\"items\":[{\"skuId\":" + skuId + ",\"quantity\":1},{\"skuId\":" + secondSkuId
                        + ",\"quantity\":1}],\"addressId\":" + addressId + "}", token);
        assertThat(order.status()).as("双明细下单失败:%s", order.body()).isEqualTo(200);
        long orderId = Long.parseLong(order.text("orderId"));
        assertThat(post("/pay/callback/wx", "{\"outTradeNo\":\"" + order.text("orderNo")
                + "\",\"transactionId\":\"e2e-partial-" + System.nanoTime()
                + "\",\"amount\":120.00,\"success\":true,\"rawBody\":\"{}\"}", null).code())
                .as("支付回调").isZero();
        inTenant(() -> {
            orderAdminService.ship(orderId, "顺丰速运", "SF" + System.nanoTime());
            return null;
        });
        assertThat(post("/mall/api/orders/" + orderId + "/receive", null, token).code()).as("确认收货").isZero();

        List<Long> itemIds = orderItemIds(orderId);
        assertThat(itemIds).as("双明细订单应当有两个明细").hasSize(2);

        // 只对第一件走完退货退款
        Long afterSaleId = applyAfterSale(itemIds.get(0), 2);
        assertThat(post("/mall/admin/after-sales/" + afterSaleId + "/approve", "{\"refundAmount\":60.00}",
                adminToken).code()).isZero();
        assertThat(post("/mall/api/after-sales/" + afterSaleId + "/return-logistics",
                "{\"company\":\"顺丰速运\",\"no\":\"SF-P\"}", token).code()).isZero();
        assertThat(post("/mall/admin/after-sales/" + afterSaleId + "/confirm-receive",
                "{\"logisticsCompany\":\"顺丰速运\",\"logisticsNo\":\"SF-P\"}", adminToken).code()).isZero();

        assertThat(get("/mall/api/orders/" + orderId, token).text("status"))
                .as("还有一件没退完,订单不能整单取消;它有收货时间,应当回到已完成")
                .isEqualTo("4");
    }

    /** 造一个额外的 SKU(挂在同一个商品下),用于构造双明细订单。 */
    private Long createExtraSku() {
        return inTenant(() -> {
            MallSku sku = new MallSku();
            sku.setGoodsId(goodsId);
            sku.setSkuCode("E2E-N-" + System.nanoTime());
            sku.setSkuName("第二规格");
            sku.setPrice(new BigDecimal("60.00"));
            sku.setStock(5);
            sku.setLockedStock(0);
            sku.setStatus(1);
            return skuRepository.save(sku).getId();
        });
    }

    /** 订单明细 id 列表:双明细订单要**按件**申请售后,得把两个明细都取出来。 */
    private List<Long> orderItemIds(long orderId) {
        Matcher matcher = Pattern.compile("\"id\":\"?(\\d+)\"?,\"skuId\":")
                .matcher(get("/mall/api/orders/" + orderId, token).body());
        List<Long> ids = new java.util.ArrayList<>();
        while (matcher.find()) {
            ids.add(Long.valueOf(matcher.group(1)));
        }
        return ids;
    }

    /**
     * 售后处理链上的分支:退货物流的必填、拒绝收货后可撤销/可申请介入、以及终态后订单状态的回落。
     *
     * <p>回落规则(3.9)值得单独钉:订单该回到"待发货/待收货/已完成"里的哪一个,取决于
     * {@code shipTime}、{@code receiveTime} 是否已写。判错会让已发货的订单被打回"待发货",
     * 商家那边会重新出现一条待发货单,而货其实已经在路上。
     */
    @Test
    @DisplayName("售后处理:退货物流两项都必填、拒绝收货后可撤销/可介入、终态后订单按发货收货时间回落")
    void afterSaleReturnAndArbitrationBranches() {
        login();
        Long addressId = createAddress("e2e退货链", "13900000052");

        // ① 退货物流的公司与单号必须都填;拒绝收货不强制原因;撤销后订单回落到"待收货"
        long[] first = createPaidOrder(addressId, 1);
        inTenant(() -> {
            orderAdminService.ship(first[0], "顺丰速运", "SF" + System.nanoTime());
            return null;
        });
        Long firstAfterSale = applyAfterSale(first[1], 2);
        assertThat(post("/mall/admin/after-sales/" + firstAfterSale + "/approve", "{\"refundAmount\":60.00}",
                adminToken).code()).as("同意退货").isZero();

        assertThat(post("/mall/api/after-sales/" + firstAfterSale + "/return-logistics",
                "{\"company\":\"  \",\"no\":\"SF-A\"}", token).code())
                .as("只填单号不填公司要拒").isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(post("/mall/api/after-sales/" + firstAfterSale + "/return-logistics",
                "{\"company\":\"顺丰速运\",\"no\":\"\"}", token).code())
                .as("只填公司不填单号要拒").isEqualTo(ErrorCode.PARAM_INVALID.code());
        assertThat(post("/mall/api/after-sales/" + firstAfterSale + "/return-logistics",
                "{\"company\":\"顺丰速运\",\"no\":\"SF-A\"}", token).code()).as("填齐才通过").isZero();

        assertThat(post("/mall/admin/after-sales/" + firstAfterSale + "/reject-receive", "{}", adminToken).code())
                .as("拒绝收货不强制填原因,日志里记「未填写原因」").isZero();
        assertThat(post("/mall/api/after-sales/" + firstAfterSale + "/cancel", null, token).code())
                .as("被拒绝收货后买家可以撤销").isZero();
        assertThat(get("/mall/api/orders/" + first[0], token).text("status"))
                .as("撤销后订单回到待收货:有发货时间、没有收货时间")
                .isEqualTo("3");

        // ② 拒绝收货后也能申请客服介入;仲裁驳回且不带备注同样要能走通
        long[] second = createPaidOrder(addressId, 1);
        inTenant(() -> {
            orderAdminService.ship(second[0], "顺丰速运", "SF" + System.nanoTime());
            return null;
        });
        Long secondAfterSale = applyAfterSale(second[1], 2);
        assertThat(post("/mall/admin/after-sales/" + secondAfterSale + "/approve", "{\"refundAmount\":60.00}",
                adminToken).code()).isZero();
        assertThat(post("/mall/api/after-sales/" + secondAfterSale + "/return-logistics",
                "{\"company\":\"顺丰速运\",\"no\":\"SF-B\"}", token).code()).isZero();
        assertThat(post("/mall/admin/after-sales/" + secondAfterSale + "/reject-receive", "{}", adminToken).code())
                .isZero();
        assertThat(post("/mall/api/after-sales/" + secondAfterSale + "/arbitration", null, token).code())
                .as("被拒绝收货后同样可以申请客服介入").isZero();
        assertThat(post("/mall/admin/after-sales/" + secondAfterSale + "/arbitrate", "{\"pass\":false}", adminToken)
                .code()).as("仲裁驳回不带备注也要能走通").isZero();

        // ③ 仅退款被拒 → 申请介入 → 仲裁通过(不带备注);未发货订单应回落到"待发货"
        long[] third = createPaidOrder(addressId, 1);
        Long thirdAfterSale = applyAfterSale(third[1], 1);
        assertThat(post("/mall/admin/after-sales/" + thirdAfterSale + "/reject", "{}", adminToken).code())
                .as("拒绝时不填原因也要能走通").isZero();
        assertThat(post("/mall/api/after-sales/" + thirdAfterSale + "/arbitration", null, token).code()).isZero();
        assertThat(post("/mall/admin/after-sales/" + thirdAfterSale + "/arbitrate", "{\"pass\":true}", adminToken)
                .code()).as("仲裁通过不带备注也要能走通").isZero();
        assertThat(get("/mall/api/orders/" + third[0], token).text("status"))
                .as("这一单只有一个明细且已退款完成 → 整单退掉,订单终态是已取消(3.9),不是待发货")
                .isEqualTo("5");
    }

    /**
     * 商家售后:五个动作各自只认自己的前置状态。
     *
     * <p>与订单同理,这里额外钉住一条**金额**规则:同意时只能下调退款金额、不能上调(3.9),
     * 上调等于商家替买家把退款金额改大了 —— 这种越权改金额的问题不会报错,只会静默多退钱。
     */
    @Test
    @DisplayName("商家售后:终态不能重复处理、退款金额不能上调、没寄回不能确认收货")
    void merchantAfterSaleRejectsIllegalTransitions() {
        login();
        Long addressId = createAddress("e2e售后越界", "13900000035");

        // ① 退款金额高于申请金额要被拒:同意动作只能下调
        long[] first = createPaidOrder(addressId, 1);
        Long as1 = applyAfterSale(first[1], 1);
        Response overRefund = post("/mall/admin/after-sales/" + as1 + "/approve",
                "{\"refundAmount\":6000.00}", adminToken);
        assertThat(overRefund.code())
                .as("退款金额不能高于申请金额,响应=%s", overRefund.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        // ② 同意之后单据进入终态,再同意或再拒绝都要被拒
        assertThat(post("/mall/admin/after-sales/" + as1 + "/approve", "{\"refundAmount\":60.00}", adminToken)
                .code()).as("首次同意应当成功").isZero();
        Response approveAgain = post("/mall/admin/after-sales/" + as1 + "/approve",
                "{\"refundAmount\":60.00}", adminToken);
        assertThat(approveAgain.code())
                .as("已同意的售后单不能重复同意,响应=%s", approveAgain.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
        Response rejectDone = post("/mall/admin/after-sales/" + as1 + "/reject",
                "{\"reason\":\"反悔了\"}", adminToken);
        assertThat(rejectDone.code())
                .as("终态售后单不能拒绝,响应=%s", rejectDone.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        // ③ 仲裁只在"客服介入中"成立:没被拒绝过的单据谈不上介入
        Response arbitrate = post("/mall/admin/after-sales/" + as1 + "/arbitrate",
                "{\"pass\":true,\"remark\":\"直接仲裁\"}", adminToken);
        assertThat(arbitrate.code())
                .as("非客服介入中的售后单不能仲裁,响应=%s", arbitrate.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        // ④ 确认收货只认"待商家收货":刚申请的单子买家还没寄回
        long[] second = createPaidOrder(addressId, 1);
        Long as2 = applyAfterSale(second[1], 2);
        Response confirmTooEarly = post("/mall/admin/after-sales/" + as2 + "/confirm-receive",
                "{\"logisticsCompany\":\"顺丰速运\",\"logisticsNo\":\"SF-C\"}", adminToken);
        assertThat(confirmTooEarly.code())
                .as("买家还没寄回就确认收货要被拒,响应=%s", confirmTooEarly.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
    }

    @Test
    @DisplayName("评价:买家评价后商家可回复,隐藏后不在展示列表里")
    void reviewReplyAndHide() {
        login();
        Long addressId = createAddress("e2e评价", "13900000033");

        // 评价要求订单已完成(3.8):支付 → 发货 → 确认收货
        long[] paid = createPaidOrder(addressId, 1);
        inTenant(() -> {
            orderAdminService.ship(paid[0], "顺丰速运", "SF" + System.nanoTime());
            return null;
        });
        assertThat(post("/mall/api/orders/" + paid[0] + "/receive", null, token).code()).isZero();

        String comment = "端到端好评" + System.nanoTime() % 100000;
        Response created = post("/mall/api/reviews",
                "{\"orderItemId\":" + paid[1] + ",\"rating\":5,\"content\":\"" + comment + "\",\"anonymous\":false}",
                token);
        assertThat(created.status()).as("提交评价失败:%s", created.body()).isEqualTo(200);
        Long reviewId = Long.valueOf(created.text("data"));

        // 商品评价列表是公开接口(游客也能看)
        assertThat(get("/mall/api/reviews/goods/" + goodsId, null).body()).contains(comment);

        assertThat(put("/mall/admin/reviews/" + reviewId + "/reply", "{\"content\":\"感谢支持\"}", adminToken).code())
                .as("商家回复").isZero();
        assertThat(get("/mall/api/reviews/goods/" + goodsId, null).body())
                .as("回复要能展示给买家").contains("感谢支持");

        assertThat(put("/mall/admin/reviews/" + reviewId + "/status?status=0", null, adminToken).code())
                .as("隐藏评价").isZero();
        assertThat(get("/mall/api/reviews/goods/" + goodsId, null).body())
                .as("隐藏后不该再出现在展示列表里").doesNotContain(comment);
    }

    @Test
    @DisplayName("商家评价:空白回复与非 0/1 的状态值都要被拒")
    void reviewModerationRejectsBadInput() {
        login();
        Long addressId = createAddress("e2e评价越界", "13900000037");
        Long reviewId = createReview(addressId);

        // 空白回复:不挡住的话评价区会挂一条空回复,买家看着像商家没说话
        Response blank = put("/mall/admin/reviews/" + reviewId + "/reply", "{\"content\":\"   \"}", adminToken);
        assertThat(blank.code())
                .as("回复内容不能是空白,响应=%s", blank.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        // 状态只接受 0(隐藏)/1(展示):传别的值是调用方写错,要挡住而不是当成隐藏
        Response badStatus = put("/mall/admin/reviews/" + reviewId + "/status?status=2", null, adminToken);
        assertThat(badStatus.code())
                .as("评价状态只能是 0 或 1,响应=%s", badStatus.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
    }

    /**
     * 商家订单列表的筛选参数。
     *
     * <p>现有用例只传分页参数就断言"列表能返回",**筛选条件是不是真的生效从没验过** ——
     * 查询谓词写漏的后果是筛任何条件都返回全量,商家看不出来,只觉得"单子好多"。
     */
    @Test
    @DisplayName("商家订单列表:按订单号与状态筛选要真的生效")
    void adminOrderListFiltering() {
        login();
        Long addressId = createAddress("e2e订单筛选", "13900000036");

        long unpaid = createOrderOnly(addressId, 1);
        String unpaidNo = get("/mall/api/orders/" + unpaid, token).text("orderNo");
        long[] paid = createPaidOrder(addressId, 1);
        String paidNo = get("/mall/api/orders/" + paid[0], token).text("orderNo");
        assertThat(unpaidNo).as("未付款单号").isNotBlank();
        assertThat(paidNo).as("已付款单号").isNotBlank();

        // 按订单号:命中自己,且不串到另一单
        Response byNo = get("/mall/admin/orders?orderNo=" + paidNo + "&pageSize=100", adminToken);
        assertThat(byNo.code()).as("订单列表查询失败:%s", byNo.body()).isZero();
        assertThat(byNo.body()).as("按订单号筛选要命中这一单").contains(paidNo);
        assertThat(byNo.body()).as("按订单号筛选不该带上别的单").doesNotContain(unpaidNo);

        // 按状态:待付款(1)与待发货(2)互不串台。
        // 这里**同时带上订单号**:单靠 status 的话结果集是整个租户的订单,断言"这一单在不在里面"
        // 就多了一层"pageSize=100 装得下"的假设 —— 那与业务无关,订单一多就会红。
        // 收敛到具体单号之后,验的仍然是 status 谓词本身,但不再依赖分页装得下
        Response pendingPay = get("/mall/admin/orders?orderNo=" + unpaidNo + "&status=1&pageSize=100", adminToken);
        assertThat(pendingPay.body()).as("待付款页签要包含未付款单").contains(unpaidNo);
        assertThat(get("/mall/admin/orders?orderNo=" + paidNo + "&status=1&pageSize=100", adminToken).body())
                .as("待付款页签不该包含已付款单").doesNotContain(paidNo);

        Response pendingShip = get("/mall/admin/orders?orderNo=" + paidNo + "&status=2&pageSize=100", adminToken);
        assertThat(pendingShip.body()).as("待发货页签要包含已付款单").contains(paidNo);
        assertThat(get("/mall/admin/orders?orderNo=" + unpaidNo + "&status=2&pageSize=100", adminToken).body())
                .as("待发货页签不该包含未付款单").doesNotContain(unpaidNo);
    }

    @Test
    @DisplayName("商家售后列表:按状态与售后单号筛选要真的生效,处理完就不该再出现在待处理里")
    void adminAfterSaleListFiltering() {
        login();
        Long addressId = createAddress("e2e售后筛选", "13900000038");

        long[] paid = createPaidOrder(addressId, 1);
        Long afterSaleId = applyAfterSale(paid[1], 1);
        String afterSaleNo = get("/mall/api/after-sales/" + afterSaleId, token).text("afterSaleNo");
        assertThat(afterSaleNo).as("售后单号").isNotBlank();

        // 断言统一带上售后单号,把查询收敛到这一单 —— 单靠 status 会依赖"pageSize=100 装得下
        // 整个租户的待处理售后",那是与业务无关的假设;带上单号后验的仍是 status 谓词本身,
        // 且不受分页与数据量影响
        String byNoPath = "/mall/admin/after-sales?afterSaleNo=" + afterSaleNo;

        // 待商家处理 = 1:新申请的单子必须在这儿,否则商家根本看不到它
        Response pending = get(byNoPath + "&status=1&pageSize=100", adminToken);
        assertThat(pending.code()).as("售后列表查询失败:%s", pending.body()).isZero();
        assertThat(pending.body()).as("待处理列表要包含这一单").contains("\"id\":\"" + afterSaleId + "\"");

        // 只按单号、不带 status 也要命中:两条谓词各自独立
        Response byNo = get(byNoPath + "&pageSize=100", adminToken);
        assertThat(byNo.code()).as("按售后单号查询失败:%s", byNo.body()).isZero();
        assertThat(byNo.body()).as("按单号筛选要命中这一单").contains("\"id\":\"" + afterSaleId + "\"");
        assertThat(get("/mall/admin/after-sales?afterSaleNo=NOSUCH" + System.nanoTime() + "&pageSize=100", adminToken)
                .body())
                .as("按不存在的单号筛选不该命中").doesNotContain("\"id\":\"" + afterSaleId + "\"");

        assertThat(post("/mall/admin/after-sales/" + afterSaleId + "/approve",
                "{\"refundAmount\":60.00}", adminToken).code()).as("同意售后").isZero();

        assertThat(get(byNoPath + "&status=1&pageSize=100", adminToken).body())
                .as("处理完就不该再出现在待处理列表里").doesNotContain("\"id\":\"" + afterSaleId + "\"");
    }

    /**
     * 客户端三个列表的筛选参数(设计文档 3.2、3.6)。
     *
     * <p>端上的"我的订单"要按状态分页签、"分类页/搜索"要按分类与关键字、"我的券"要按
     * 未使用/已使用切换 —— 这些都是前端传参、后端拼谓词。谓词写漏的表现是切页签不生效,
     * 而不是报错,所以两侧都要断言。
     */
    @Test
    @DisplayName("客户端列表:我的订单按状态、商品按分类与关键字、我的券按状态筛选都要真的生效")
    void clientListFiltering() {
        login();
        Long addressId = createAddress("e2e客户端筛选", "13900000044");

        // ① 我的订单:待付款(1)与待发货(2)互不串台
        long unpaid = createOrderOnly(addressId, 1);
        String unpaidNo = get("/mall/api/orders/" + unpaid, token).text("orderNo");
        long[] paid = createPaidOrder(addressId, 1);
        String paidNo = get("/mall/api/orders/" + paid[0], token).text("orderNo");

        Response pendingPay = get("/mall/api/orders?status=1&pageSize=100", token);
        assertThat(pendingPay.code()).as("我的订单查询失败:%s", pendingPay.body()).isZero();
        assertThat(pendingPay.body()).as("待付款页签要包含未付款单").contains(unpaidNo);
        assertThat(pendingPay.body()).as("待付款页签不该包含已付款单").doesNotContain(paidNo);

        Response pendingShip = get("/mall/api/orders?status=2&pageSize=100", token);
        assertThat(pendingShip.body()).as("待发货页签要包含已付款单").contains(paidNo);
        assertThat(pendingShip.body()).as("待发货页签不该包含未付款单").doesNotContain(unpaidNo);

        // ② 商品列表:按关键字与分类筛选(都是公开接口,不带令牌)。
        //    这里自己造一个"名字 + 分类"都唯一的在售商品,而不是复用 setUp 的 fixture:
        //    fixture 的名字固定、分类是 0,而整轮跑下来分类为 0 的在售商品可能有上百条
        //    (多个服务层用例也往同一个租户建),查询收敛不到它,断言就变成"赌它落在第一页"
        String uniqueName = "e2e筛选商品" + System.nanoTime() % 1000000;
        Long uniqueCategory = 900000000L + System.nanoTime() % 1000000;
        Long uniqueGoods = inTenant(() -> {
            MallGoods goods = new MallGoods();
            goods.setCategoryId(uniqueCategory);
            goods.setGoodsName(uniqueName);
            goods.setMainImage("https://example.com/unique.png");
            goods.setSalePriceMin(new BigDecimal("1.00"));
            goods.setSalePriceMax(new BigDecimal("1.00"));
            goods.setTotalStock(1);
            goods.setSaleCount(0);
            goods.setStatus(1);
            goods.setSortOrder(0);
            return goodsRepository.save(goods).getId();
        });

        try {
            assertThat(get("/mall/api/goods?keyword=" + enc(uniqueName) + "&pageSize=50", null).body())
                    .as("按商品名关键字要能搜到").contains("\"id\":\"" + uniqueGoods + "\"");
            assertThat(get("/mall/api/goods?keyword=" + enc("绝无此商品" + System.nanoTime()) + "&pageSize=50", null)
                    .body())
                    .as("按不存在的关键字不该命中").doesNotContain("\"id\":\"" + uniqueGoods + "\"");
            assertThat(get("/mall/api/goods?categoryId=" + uniqueCategory + "&pageSize=50", null).body())
                    .as("按分类筛选要能查到").contains("\"id\":\"" + uniqueGoods + "\"");
            assertThat(get("/mall/api/goods?categoryId=999999&pageSize=50", null).body())
                    .as("按不存在的分类不该命中").doesNotContain("\"id\":\"" + uniqueGoods + "\"");
        } finally {
            inTenant(() -> {
                goodsRepository.findById(uniqueGoods).ifPresent(goodsRepository::delete);
                return null;
            });
        }

        // ③ 我的券:刚领到的券是"未使用"(1),不该出现在"已使用"(2)里
        String couponName = "e2e筛选券" + System.nanoTime() % 100000;
        Long couponId = inTenant(() -> couponService.create(new CouponSaveRequest(
                couponName, MallCoupon.TYPE_FULL_REDUCTION, new BigDecimal("5.00"), null, new BigDecimal("0"),
                100, 1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), 1)));
        assertThat(post("/mall/api/coupons/" + couponId + "/claim", null, token).code()).isZero();

        Response unused = get("/mall/api/coupons/mine?status=1", token);
        assertThat(unused.code()).as("我的券查询失败:%s", unused.body()).isZero();
        assertThat(unused.body()).as("未使用页签要包含刚领的券").contains(couponName);
        assertThat(get("/mall/api/coupons/mine?status=2", token).body())
                .as("已使用页签不该包含未使用的券").doesNotContain(couponName);
    }

    /**
     * prepay 的另两条分支。
     *
     * <p>0 元订单(全额优惠把应付抵成 0)不该去调支付渠道 —— 渠道那边没有"0 元交易"这回事,
     * 硬调只会拿到一个错误。重复拉起则要复用第一次建的预支付单,而不是每次都新建一条支付流水
     * (流水会越攒越多,而对账时只看得到最新一条)。
     */
    @Test
    @DisplayName("拉起支付:0 元订单不走支付渠道;同一单重复拉起复用已建的预支付单")
    void prepaySkipsZeroAmountAndReusesPendingPayment() {
        login();
        Long addressId = createAddress("e2e预支付", "13900000042");

        // ① 0 元订单:直接改库把应付抵成 0,模拟"优惠券把金额减完了"
        long zeroOrder = createOrderOnly(addressId, 1);
        inTenant(() -> {
            orderRepository.findById(zeroOrder).ifPresent(order -> {
                order.setPayAmount(BigDecimal.ZERO);
                orderRepository.save(order);
            });
            return null;
        });
        Response zero = post("/mall/api/orders/" + zeroOrder + "/prepay", null, token);
        assertThat(zero.code())
                .as("0 元订单无需支付,响应=%s", zero.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        // ② 重复拉起:两次都要成功,第二次复用第一次的预支付单
        long second = createOrderOnly(addressId, 1);
        assertThat(post("/mall/api/orders/" + second + "/prepay", null, token).code())
                .as("首次拉起支付").isZero();
        Response again = post("/mall/api/orders/" + second + "/prepay", null, token);
        assertThat(again.code())
                .as("重复拉起应当复用已建的预支付单,响应=%s", again.body()).isZero();
    }

    /**
     * 迟到的支付回调:订单已经被关闭了,钱却收成功了。
     *
     * <p>这是真实会发生的场景(买家卡在支付页、订单被超时任务关掉、然后支付完成)。
     * 处理方式是**不在这里静默改订单状态** —— 货已经释放了,改回去会造成超卖;
     * 这笔钱要走退款流程退给买家,所以回调只记日志并返回成功。
     */
    @Test
    @DisplayName("支付回调:订单已关闭时不能置为已支付(钱已收、货已释放,留给退款流程)")
    void payCallbackIgnoresClosedOrder() {
        login();
        Long addressId = createAddress("e2e关闭订单回调", "13900000043");

        long orderId = createOrderOnly(addressId, 1);
        String orderNo = get("/mall/api/orders/" + orderId, token).text("orderNo");
        assertThat(post("/mall/api/orders/" + orderId + "/prepay", null, token).code())
                .as("拉起支付").isZero();

        assertThat(post("/mall/api/orders/" + orderId + "/cancel", null, token).code())
                .as("买家取消").isZero();
        assertThat(get("/mall/api/orders/" + orderId, token).text("status"))
                .as("取消后不再是待付款").isNotEqualTo("1");

        Response lateCallback = post("/pay/callback/wx",
                "{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"e2e-late\","
                        + "\"amount\":60.00,\"success\":true,\"rawBody\":\"{}\"}", null);
        assertThat(lateCallback.code())
                .as("迟到的回调不能报错(报错会让微信一直重推),响应=%s", lateCallback.body()).isZero();
        assertThat(get("/mall/api/orders/" + orderId, token).text("status"))
                .as("已关闭的订单不能被回调复活").isNotEqualTo("2");
    }

    /**
     * 支付回调的守卫(商城设计文档 3.8)。
     *
     * <p>这是全项目**唯一一条没有登录态、由外部系统调用**的链路,它的守卫写漏直接等于钱货两空:
     * 金额不校验,买家改小金额就能提货;不幂等,微信重推一次就多扣一次库存。
     * 这些分支在服务层用例里走不到 —— 回调根本没有租户上下文,必须由 HTTP 入口触发。
     */
    @Test
    @DisplayName("支付回调:金额不符整笔拒绝、失败回调不动订单、重复回调幂等、未知单号忽略")
    void payCallbackGuards() {
        login();
        Long addressId = createAddress("e2e支付回调", "13900000040");

        long orderId = createOrderOnly(addressId, 1);
        String orderNo = get("/mall/api/orders/" + orderId, token).text("orderNo");
        assertThat(orderNo).as("订单号").isNotBlank();
        // 支付流水是 prepay 建的,不先拉起支付的话回调找不到流水(会走"忽略"分支)
        assertThat(post("/mall/api/orders/" + orderId + "/prepay", null, token).code())
                .as("拉起支付").isZero();

        // ① 金额不一致:改小金额也要整笔拒绝,否则买家付 1 分钱就能拿货
        Response mismatched = post("/pay/callback/wx",
                "{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"e2e-x\","
                        + "\"amount\":0.01,\"success\":true,\"rawBody\":\"{}\"}", null);
        assertThat(mismatched.code())
                .as("金额不一致必须整笔拒绝,响应=%s", mismatched.body())
                .isEqualTo(ErrorCode.DATA_CONFLICT.code());
        assertThat(get("/mall/api/orders/" + orderId, token).text("status"))
                .as("拒绝后订单不能变成已支付").isNotEqualTo("2");

        // ② 支付失败:只标记流水,订单仍然待付款(由超时任务或用户取消驱动)
        Response failed = post("/pay/callback/wx",
                "{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"e2e-x\","
                        + "\"amount\":60.00,\"success\":false,\"rawBody\":\"{}\"}", null);
        assertThat(failed.code())
                .as("失败回调本身要返回成功(否则微信会一直重推),响应=%s", failed.body()).isZero();
        assertThat(get("/mall/api/orders/" + orderId, token).text("status"))
                .as("失败回调不该动订单状态").isEqualTo("1");

        // ③ 正常支付 → 待发货;④ 再来一次同样的回调要幂等
        assertThat(post("/pay/callback/wx",
                "{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"e2e-ok\","
                        + "\"amount\":60.00,\"success\":true,\"rawBody\":\"{}\"}", null).code())
                .as("支付回调").isZero();
        assertThat(get("/mall/api/orders/" + orderId, token).text("status")).isEqualTo("2");

        Response replay = post("/pay/callback/wx",
                "{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"e2e-ok\","
                        + "\"amount\":60.00,\"success\":true,\"rawBody\":\"{}\"}", null);
        assertThat(replay.code()).as("重复回调要幂等,响应=%s", replay.body()).isZero();
        assertThat(get("/mall/api/orders/" + orderId, token).text("status"))
                .as("重复回调不该再扣一次库存或改状态").isEqualTo("2");

        // ⑤ 找不到支付流水的回调要忽略而不是报错:微信重推旧单,重推一百次也不会自己好
        Response unknown = post("/pay/callback/wx",
                "{\"outTradeNo\":\"绝不存在的商户单号\",\"transactionId\":\"e2e-x\","
                        + "\"amount\":60.00,\"success\":true,\"rawBody\":\"{}\"}", null);
        assertThat(unknown.code()).as("未知单号的回调要忽略,响应=%s", unknown.body()).isZero();
    }

    /**
     * prepay 的三条拒绝路径。
     *
     * <p>② 与 ③ 是**同一个码的两侧**:不存在的订单、以及存在但属于别人的订单,都必须回 40400。
     * 只测 ② 的话,归属判断写漏(比如查询只按 orderId 不按 customerId)照样是绿的 ——
     * 而它的后果是任何人猜到订单 id 就能替别人拉起支付。所以 ③ 用同一个客户之外的令牌真的打一次。
     */
    @Test
    @DisplayName("拉起支付:已支付的订单不能再拉起,不存在与别人的订单都当不存在")
    void prepayRejectsWrongStateAndForeignOrder() {
        login();
        Long addressId = createAddress("e2e拉起支付", "13900000041");

        // ① 已支付的订单不能再拉起:状态已不是待付款
        long[] paid = createPaidOrder(addressId, 1);
        Response prepayPaid = post("/mall/api/orders/" + paid[0] + "/prepay", null, token);
        assertThat(prepayPaid.code())
                .as("已支付订单不能再拉起支付,响应=%s", prepayPaid.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        // ② 不存在的订单
        Response prepayAbsent = post("/mall/api/orders/999999999/prepay", null, token);
        assertThat(prepayAbsent.code())
                .as("不存在的订单要返回资源不存在,响应=%s", prepayAbsent.body())
                .isEqualTo(ErrorCode.NOT_FOUND.code());

        // ③ 别人的订单:先在客户 A 名下留一笔待付款订单,再以客户 B 的身份去拉起它。
        //    login() 每次都换一个 openid,所以这里天然得到"另一个客户"
        long foreignOrder = createOrderOnly(addressId, 1);
        login();
        Response prepayForeign = post("/mall/api/orders/" + foreignOrder + "/prepay", null, token);
        assertThat(prepayForeign.code())
                .as("别人的订单要按不存在处理(与 ② 同一个码,不泄露它是否存在),响应=%s", prepayForeign.body())
                .isEqualTo(ErrorCode.NOT_FOUND.code());
        assertThat(get("/mall/api/orders/" + foreignOrder, token).code())
                .as("详情同理:别人的订单不能读出来")
                .isEqualTo(ErrorCode.NOT_FOUND.code());
    }

    /**
     * 商家侧的详情接口与评价列表筛选。
     *
     * <p>详情接口此前一个用例都没有:列表能返回不代表详情能返回,而详情才是商家真正点进去看的那一屏。
     * 评价列表的 status 筛选在这里用"隐藏前命中 / 隐藏后不命中"两侧钉住 —— 只断言一侧的话,
     * 谓词写漏(永远返回全量)也是绿的。
     */
    @Test
    @DisplayName("商家详情:订单与售后详情可取;评价列表按商品与状态筛选生效")
    void adminDetailAndReviewListFiltering() {
        login();
        Long addressId = createAddress("e2e后台详情", "13900000039");

        long[] paid = createPaidOrder(addressId, 1);
        String orderNo = get("/mall/api/orders/" + paid[0], token).text("orderNo");
        assertThat(orderNo).as("订单号").isNotBlank();

        Response orderDetail = get("/mall/admin/orders/" + paid[0], adminToken);
        assertThat(orderDetail.code()).as("订单详情查询失败:%s", orderDetail.body()).isZero();
        assertThat(orderDetail.body()).as("订单详情要带上订单号与收货信息,否则商家没法发货")
                .contains(orderNo).contains("e2e后台详情");

        Long afterSaleId = applyAfterSale(paid[1], 1);
        Response afterSaleDetail = get("/mall/admin/after-sales/" + afterSaleId, adminToken);
        assertThat(afterSaleDetail.code()).as("售后详情查询失败:%s", afterSaleDetail.body()).isZero();
        assertThat(afterSaleDetail.body()).as("售后详情要带回这一单").contains("\"id\":\"" + afterSaleId + "\"");

        Long reviewId = createReview(addressId);
        Response visible = get("/mall/admin/reviews?goodsId=" + goodsId + "&status=1&pageSize=50", adminToken);
        assertThat(visible.code()).as("评价列表查询失败:%s", visible.body()).isZero();
        assertThat(visible.body()).as("按商品 + 展示状态筛选要命中这条评价").contains("\"id\":\"" + reviewId + "\"");

        assertThat(put("/mall/admin/reviews/" + reviewId + "/status?status=0", null, adminToken).code()).isZero();
        assertThat(get("/mall/admin/reviews?goodsId=" + goodsId + "&status=1&pageSize=50", adminToken).body())
                .as("隐藏后不该再出现在展示状态里").doesNotContain("\"id\":\"" + reviewId + "\"");
        assertThat(get("/mall/admin/reviews?goodsId=" + goodsId + "&status=0&pageSize=50", adminToken).body())
                .as("按隐藏状态筛选应当命中它").contains("\"id\":\"" + reviewId + "\"");
    }

    // ---------------------------------------------------------------- 客户端写接口

    /**
     * 个人资料更新的两个分支。
     *
     * <p>性别是枚举值(0 未知 / 1 男 / 2 女),传别的值必须挡住 —— 存进去之后前端渲染会拿到一个
     * 不认识的枚举,而这类"脏值进库"后面要清理就得刷数据。
     * 空白昵称则是**跳过而不是覆盖**:小程序端经常整个表单提交,空字段不该把已有值清掉。
     */
    @Test
    @DisplayName("个人资料:性别取值非法要被拒,空白昵称不影响已有值")
    void profileUpdateValidatesGenderAndSkipsBlank() {
        login();
        String nickname = "e2e昵称" + System.nanoTime() % 100000;
        assertThat(put("/mall/api/profile", "{\"nickname\":\"" + nickname + "\"}", token).code())
                .as("先设一个昵称").isZero();

        Response badGender = put("/mall/api/profile", "{\"gender\":9}", token);
        assertThat(badGender.code())
                .as("性别只能是 0/1/2,响应=%s", badGender.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());

        assertThat(put("/mall/api/profile", "{\"nickname\":\"   \"}", token).code())
                .as("空白昵称应当被跳过").isZero();
        assertThat(get("/mall/api/profile", token).body()).as("已有昵称不能被空白值清掉").contains(nickname);
    }

    @Test
    @DisplayName("地址:新增 → 改名 → 设默认(自动清掉其他默认)→ 删除")
    void addressCrudCycle() {
        login();
        createAddress("e2e默认一号", "13900000021");
        Long second = createAddress("e2e默认二号", "13900000022");

        assertThat(get("/mall/api/addresses", token).body())
                .contains("e2e默认一号").contains("e2e默认二号");

        // 库上注释写明了"同一客户至多一条为 1,应用层保证":这条断言就是替它把关的地方。
        // 两条都是默认时,下单用哪个地址取决于查询顺序 —— 用户会看到收货地址莫名其妙地变
        assertThat(put("/mall/api/addresses/" + second + "/default", null, token).status()).isEqualTo(200);
        String list = get("/mall/api/addresses", token).body();
        assertThat(countOf(list, "\"isDefault\":1")).as("默认地址只能有一条").isEqualTo(1);
        assertThat(put("/mall/api/addresses/" + second, addressBody("e2e默认二号改", "13900000022"), token).status())
                .isEqualTo(200);
        assertThat(get("/mall/api/addresses", token).body()).contains("e2e默认二号改");

        assertThat(delete("/mall/api/addresses/" + second, null, token).status()).isEqualTo(200);
        assertThat(get("/mall/api/addresses", token).body()).doesNotContain("e2e默认二号改");
    }

    @Test
    @DisplayName("购物车:改数量 → 勾选 → 删除条目 → 清空")
    void cartUpdateRemoveAndClear() {
        login();
        addToCart(skuId, 2);
        Long cartId = firstCartId();

        assertThat(put("/mall/api/cart/" + cartId, "{\"quantity\":3,\"selected\":1}", token).status()).isEqualTo(200);
        String afterUpdate = get("/mall/api/cart", token).body();
        assertThat(afterUpdate).contains("\"quantity\":3");
        assertThat(afterUpdate).as("勾选状态是存在服务端的(换设备仍保留)").contains("\"selected\":1");

        // 取消勾选:下单只取已勾选条目,所以这个字段错了会表现成"下单少买了东西"
        assertThat(put("/mall/api/cart/" + cartId, "{\"quantity\":3,\"selected\":0}", token).status()).isEqualTo(200);
        assertThat(get("/mall/api/cart", token).body()).contains("\"selected\":0");

        assertThat(delete("/mall/api/cart", "[" + cartId + "]", token).status()).isEqualTo(200);
        assertThat(get("/mall/api/cart", token).body()).doesNotContain("端到端测试商品");

        // 清空走独立的接口,不要自己拼"全部 id"的列表(本地列表可能已经过期)
        addToCart(skuId, 1);
        assertThat(delete("/mall/api/cart/all", null, token).status()).isEqualTo(200);
        assertThat(get("/mall/api/cart", token).body()).doesNotContain("端到端测试商品");
    }

    @Test
    @DisplayName("订单:待付款可取消;已发货可确认收货")
    void orderCancelAndReceive() {
        login();
        Long addressId = createAddress("e2e订单", "13900000023");

        // ① 待付款 → 取消
        long unpaidOrderId = createOrderOnly(addressId, 1);
        Response cancel = post("/mall/api/orders/" + unpaidOrderId + "/cancel", null, token);
        assertThat(cancel.status()).isEqualTo(200);
        assertThat(cancel.code()).as("待付款订单应当可取消:%s", cancel.body()).isZero();
        assertThat(get("/mall/api/orders/" + unpaidOrderId, token).text("status"))
                .as("取消后状态要真的变了")
                .isNotEqualTo("1");

        // ② 已支付 → 商家发货 → 确认收货
        long[] paid = createPaidOrder(addressId, 1);
        long orderId = paid[0];
        inTenant(() -> {
            // 发货这条通路由管理端用例覆盖,这里只把它当夹具
            orderAdminService.ship(orderId, "顺丰速运", "SF" + System.nanoTime());
            return null;
        });
        assertThat(get("/mall/api/orders/" + orderId, token).text("status")).isEqualTo("3");

        Response receive = post("/mall/api/orders/" + orderId + "/receive", null, token);
        assertThat(receive.status()).isEqualTo(200);
        assertThat(receive.code()).as("已发货订单应当可确认收货:%s", receive.body()).isZero();
        assertThat(get("/mall/api/orders/" + orderId, token).text("status"))
                .as("确认收货后进入已完成").isEqualTo("4");
    }

    @Test
    @DisplayName("领券:同一张券每人限领一次,超过限领要拒绝")
    void couponClaimIsLimitedPerCustomer() {
        String couponName = "e2e券" + System.nanoTime() % 100000;
        Long couponId = inTenant(() -> couponService.create(new CouponSaveRequest(couponName,
                MallCoupon.TYPE_FULL_REDUCTION, new BigDecimal("5.00"), null, new BigDecimal("0"),
                100, 1, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), 1)));

        login();
        Response first = post("/mall/api/coupons/" + couponId + "/claim", null, token);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.code()).as("首次领取应当成功:%s", first.body()).isZero();
        assertThat(get("/mall/api/coupons/mine", token).body())
                .as("领到的券要能在我的券里看到").contains(couponName);

        Response second = post("/mall/api/coupons/" + couponId + "/claim", null, token);
        assertThat(second.code())
                .as("每人限领 1 张,第二次必须被拒(否则可以无限领券)")
                .isNotZero();
    }

    /**
     * 领券的两条拒绝路径。
     *
     * <p>与"每人限领"是两件事,别混:限领是 {@code per_customer_limit},而 {@code total_count}
     * 是**券的发放量** —— 它发完就没了。只测限领的话,发完这一侧写漏会变成"券可以超发",
     * 商家要为超出预算的部分买单。
     */
    @Test
    @DisplayName("领券:发放量耗尽后拒绝;未到领取时间拒绝")
    void couponClaimRejectsExhaustedAndOutOfWindow() {
        // 只发 1 张:第一个客户领走后库存即耗尽
        Long exhausted = inTenant(() -> couponService.create(new CouponSaveRequest(
                "e2e发完券" + System.nanoTime() % 100000, MallCoupon.TYPE_FULL_REDUCTION,
                new BigDecimal("5.00"), null, new BigDecimal("0"), 1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), 1)));

        login();
        assertThat(post("/mall/api/coupons/" + exhausted + "/claim", null, token).code())
                .as("第一张应当领到").isZero();

        // 每次 login() 都是新的 openid → 另一个客户,绕开"每人限领"这条规则
        login();
        Response second = post("/mall/api/coupons/" + exhausted + "/claim", null, token);
        assertThat(second.code())
                .as("券发完后必须拒绝(发放量是库存,不是每人限领次数),响应=%s", second.body())
                .isEqualTo(ErrorCode.DATA_CONFLICT.code());

        Long notStarted = inTenant(() -> couponService.create(new CouponSaveRequest(
                "e2e未开始券" + System.nanoTime() % 100000, MallCoupon.TYPE_FULL_REDUCTION,
                new BigDecimal("5.00"), null, new BigDecimal("0"), 100, 1,
                LocalDateTime.now().plusDays(7), LocalDateTime.now().plusDays(30), 1)));
        Response tooEarly = post("/mall/api/coupons/" + notStarted + "/claim", null, token);
        assertThat(tooEarly.code())
                .as("未到领取时间要拒绝,响应=%s", tooEarly.body())
                .isEqualTo(ErrorCode.PARAM_INVALID.code());
    }

    @Test
    @DisplayName("售后:被拒后可撤销、可申请客服介入;退货退款提交退货物流")
    void afterSaleCancelLogisticsAndArbitration() {
        login();
        Long addressId = createAddress("e2e售后", "13900000024");

        // ① 仅退款 → 商家拒绝 → 买家撤销
        long[] first = createPaidOrder(addressId, 1);
        Long firstAfterSale = applyAfterSale(first[1], 1);
        inTenant(() -> {
            afterSaleService.reject(firstAfterSale, "不符合仅退款条件");
            return null;
        });
        Response cancel = post("/mall/api/after-sales/" + firstAfterSale + "/cancel", null, token);
        assertThat(cancel.status()).isEqualTo(200);
        assertThat(cancel.code()).as("被拒的售后可以撤销:%s", cancel.body()).isZero();

        // ② 退货退款 → 商家同意 → 买家提交退货物流
        long[] second = createPaidOrder(addressId, 1);
        Long secondAfterSale = applyAfterSale(second[1], 2);
        inTenant(() -> {
            afterSaleService.approve(secondAfterSale, new BigDecimal("60.00"));
            return null;
        });
        Response logistics = post("/mall/api/after-sales/" + secondAfterSale + "/return-logistics",
                "{\"company\":\"顺丰速运\",\"no\":\"SF" + System.nanoTime() + "\"}", token);
        assertThat(logistics.status()).isEqualTo(200);
        assertThat(logistics.code()).as("已同意退货时要能提交退货物流:%s", logistics.body()).isZero();
        assertThat(get("/mall/api/after-sales/" + secondAfterSale, token).body())
                .as("提交后进入待商家收货").contains("待商家收货");

        // ③ 仅退款 → 商家拒绝 → 买家申请客服介入
        long[] third = createPaidOrder(addressId, 1);
        Long thirdAfterSale = applyAfterSale(third[1], 1);
        inTenant(() -> {
            afterSaleService.reject(thirdAfterSale, "拒绝理由");
            return null;
        });
        Response arbitration = post("/mall/api/after-sales/" + thirdAfterSale + "/arbitration", null, token);
        assertThat(arbitration.status()).isEqualTo(200);
        assertThat(arbitration.code()).as("被拒后可以申请客服介入:%s", arbitration.body()).isZero();
        assertThat(get("/mall/api/after-sales/" + thirdAfterSale, token).body()).contains("客服介入中");
    }

    // ---------------------------------------------------------------- 夹具与工具

    private Long createAddress(String receiverName, String phone) {
        Response created = post("/mall/api/addresses", addressBody(receiverName, phone), token);
        assertThat(created.status()).as("建地址失败:%s", created.body()).isEqualTo(200);
        return Long.valueOf(created.text("data"));
    }

    private String addressBody(String receiverName, String phone) {
        return "{\"receiverName\":\"" + receiverName + "\",\"receiverPhone\":\"" + phone + "\","
                + "\"province\":\"广东省\",\"city\":\"深圳市\",\"district\":\"南山区\","
                + "\"detailAddress\":\"测试路 9 号\"}";
    }

    private void addToCart(Long sku, int quantity) {
        assertThat(post("/mall/api/cart", "{\"skuId\":" + sku + ",\"quantity\":" + quantity + "}", token).status())
                .isEqualTo(200);
    }

    /** 购物车条目的主键:列表里第一个 id。 */
    private Long firstCartId() {
        Matcher matcher = Pattern.compile("\"id\":\"?(\\d+)\"?").matcher(get("/mall/api/cart", token).body());
        assertThat(matcher.find()).as("购物车列表里应当能取到条目 id").isTrue();
        return Long.valueOf(matcher.group(1));
    }

    /** 只下单不支付,返回订单 id(待付款状态)。 */
    private long createOrderOnly(Long addressId, int quantity) {
        Response order = post("/mall/api/orders",
                "{\"items\":[{\"skuId\":" + skuId + ",\"quantity\":" + quantity + "}],\"addressId\":" + addressId + "}",
                token);
        assertThat(order.status()).as("下单失败:%s", order.body()).isEqualTo(200);
        return Long.parseLong(order.text("orderId"));
    }

    /** 下单并走完支付回调,返回 [订单 id, 订单明细 id](已支付 = 待发货)。 */
    private long[] createPaidOrder(Long addressId, int quantity) {
        Response order = post("/mall/api/orders",
                "{\"items\":[{\"skuId\":" + skuId + ",\"quantity\":" + quantity + "}],\"addressId\":" + addressId + "}",
                token);
        assertThat(order.status()).as("下单失败:%s", order.body()).isEqualTo(200);
        long orderId = Long.parseLong(order.text("orderId"));
        String orderNo = order.text("orderNo");
        Response callback = post("/pay/callback/wx", "{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"e2e-"
                + System.nanoTime() + "\",\"amount\":" + (60 * quantity) + ".00,\"success\":true,\"rawBody\":\"{}\"}", null);
        assertThat(callback.status()).as("支付回调失败:%s", callback.body()).isEqualTo(200);
        assertThat(get("/mall/api/orders/" + orderId, token).text("status"))
                .as("支付回调后应当进入待发货").isEqualTo("2");

        Matcher matcher = Pattern.compile("\"items\":\\[\\{\"id\":\"?(\\d+)\"?")
                .matcher(get("/mall/api/orders/" + orderId, token).body());
        assertThat(matcher.find()).as("订单详情里应当能取到明细 id").isTrue();
        return new long[]{orderId, Long.parseLong(matcher.group(1))};
    }

    private Long applyAfterSale(long orderItemId, int afterSaleType) {
        Response apply = post("/mall/api/after-sales",
                "{\"orderItemId\":" + orderItemId + ",\"afterSaleType\":" + afterSaleType
                        + ",\"applyReason\":\"端到端用例\",\"refundAmount\":60.00}", token);
        assertThat(apply.status()).as("申请售后失败:%s", apply.body()).isEqualTo(200);
        assertThat(apply.code()).isZero();
        return Long.valueOf(apply.text("data"));
    }

    /** 走完"支付 → 商家发货 → 买家确认收货"并提交评价,返回评价 id(3.8:只有已完成订单可评价)。 */
    private Long createReview(Long addressId) {
        long[] paid = createPaidOrder(addressId, 1);
        inTenant(() -> {
            orderAdminService.ship(paid[0], "顺丰速运", "SF" + System.nanoTime());
            return null;
        });
        assertThat(post("/mall/api/orders/" + paid[0] + "/receive", null, token).code())
                .as("买家确认收货").isZero();
        Response created = post("/mall/api/reviews",
                "{\"orderItemId\":" + paid[1] + ",\"rating\":5,\"content\":\"用例评价\",\"anonymous\":false}",
                token);
        assertThat(created.status()).as("提交评价失败:%s", created.body()).isEqualTo(200);
        assertThat(created.code()).as("提交评价失败:%s", created.body()).isZero();
        return Long.valueOf(created.text("data"));
    }

    private int countOf(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int index = text.indexOf(needle, from);
            if (index < 0) {
                return count;
            }
            count++;
            from = index + needle.length();
        }
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

    /** 平台超管登录:商家侧接口(/mall/admin/**)用它的令牌,权限码被 4.10 短路。 */
    private String loginAdmin() {
        Response login = post("/auth/login", "{\"tenantCode\":\"" + TENANT_CODE + "\",\"username\":\""
                + adminUsername + "\",\"password\":\"" + ADMIN_PASSWORD + "\",\"deviceId\":\"e2e-admin\"}", null);
        assertThat(login.status()).as("超管登录失败:%s", login.body()).isEqualTo(200);
        assertThat(login.code()).as("超管登录失败:%s", login.body()).isZero();
        String issued = login.text("token");
        assertThat(issued).isNotBlank();
        return issued;
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

    /**
     * 带请求体的 DELETE:删除购物车条目就是用它传 id 列表。
     *
     * <p>JDK 的 {@code HttpRequest.Builder.DELETE()} 不接受请求体(它只有无参重载),
     * 要带体必须走 {@code method("DELETE", publisher)} —— DELETE 带体虽然不常见,但本项目就是这么设计的。
     */
    private Response delete(String path, String body, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri(path))
                .header("Content-Type", "application/json");
        builder.method("DELETE", body == null ? HttpRequest.BodyPublishers.noBody()
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

    /** 查询参数的值要编码:中文与空格都不能直接进 URI。 */
    private String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
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
