package com.minimall.mall.api;

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
@ActiveProfiles("dev")
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

    // ---------------------------------------------------------------- 客户端写接口

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
        Matcher matcher = Pattern.compile("\"id\":(\\d+)").matcher(get("/mall/api/cart", token).body());
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

        Matcher matcher = Pattern.compile("\"items\":\\[\\{\"id\":(\\d+)")
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
