package com.minimall.mall.api;

import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.sys.api.dto.UserSaveRequest;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.repository.SysUserRepository;
import com.minimall.sys.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * 订单导出(商城设计文档 3.4)。
 *
 * <p>响应体是**文件字节而不是统一响应体**,所以这里断言的是 HTTP 层的东西:状态码、content-type、
 * Content-Disposition 文件名、BOM、以及内容里真的有那一行。这些错一个的表现分别是:
 * 前端下载到一个 JSON、文件名是 undefined、Excel 打开乱码、导出内容缺行。
 *
 * <p>时间范围参数单独钉一下:参数名写错时接口照样返回 200 与一份完整文件,
 * 只有"区间外的订单不该出现"这种断言才发现得了。
 *
 * <p>权限侧不在这里验:{@code AdminPermissionHttpTest} 从路由表穷举 /mall/admin/** 时已经覆盖了
 * "必须带权限码 + 无权限账号真实被拒"。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderExportHttpIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    private static final String ADMIN_PASSWORD = "Export@123456";

    @LocalServerPort
    private int port;
    @Autowired
    private UserService userService;
    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private MallOrderRepository orderRepository;
    @Autowired
    private StringRedisTemplate redis;

    private final HttpClient http = HttpClient.newHttpClient();
    private String username;
    private String orderNo;
    private Long orderId;

    @BeforeEach
    void setUpSuperUserAndOrder() {
        redis.delete(redis.keys("*127.0.0.1*"));
        username = "orderexp" + suffix();
        // 平台超管:权限短路返回全量 perm_code,不依赖租户角色的配置是否正确
        asSuperUser(() -> {
            userService.create(new UserSaveRequest(username, ADMIN_PASSWORD, "订单导出用例超管",
                    null, null, List.of(), 1));
            SysUser user = userRepository.findByTenantIdAndUsername(PLATFORM_TENANT_ID, username).orElseThrow();
            user.setIsSuper(1);
            user.setMustChangePassword(0);
            userRepository.save(user);
            return null;
        });

        // 直接落一行订单:走完整下单流程要备商品/SKU/地址,而这里要验的是导出这条出口
        orderNo = "exp" + suffix();
        orderId = asSuperUser(() -> {
            MallOrder order = new MallOrder();
            order.setOrderNo(orderNo);
            order.setCustomerId(PLATFORM_ADMIN_USER_ID);
            order.setStatus(MallOrder.STATUS_PENDING_SHIP);
            order.setGoodsAmount(new BigDecimal("60.00"));
            order.setFreightAmount(BigDecimal.ZERO);
            order.setCouponDiscountAmount(BigDecimal.ZERO);
            order.setPromotionDiscountAmount(BigDecimal.ZERO);
            // 积分两列是 NOT NULL(Hibernate 会把未赋值的字段以 NULL 送进 INSERT,库端默认值兜不住)
            order.setPointsDiscountAmount(BigDecimal.ZERO);
            order.setPointsUsed(0);
            order.setPayAmount(new BigDecimal("60.00"));
            order.setReceiverName("导出用例收货人");
            order.setReceiverPhone("13900000000");
            order.setReceiverAddress("导出用例地址");
            return orderRepository.save(order).getId();
        });
    }

    @AfterEach
    void clearContexts() {
        asSuperUser(() -> {
            orderRepository.findById(orderId).ifPresent(orderRepository::delete);
            return null;
        });
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("导出:是文件不是 JSON,带文件名与 BOM,内容里真有那一行")
    void exportsAsCsvFile() throws Exception {
        Response response = export("?orderNo=" + orderNo, login());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).as("前端据此判断拿到的是文件还是错误 JSON")
                .startsWith("text/csv");
        assertThat(response.contentDisposition()).contains("attachment").contains(".csv").contains("orders-");
        assertThat(response.body()).as("缺 BOM 的话 Excel 打开中文是乱码").startsWith("\uFEFF");
        assertThat(response.body()).as("表头要让拿到文件的人看得懂").contains("订单号,状态,买家ID");
        assertThat(response.body()).as("金额是纯数字,带了货币符号 Excel 就没法求和").contains("60.00");
        assertThat(response.body()).contains(orderNo).contains("待发货");
    }

    @Test
    @DisplayName("导出:时间范围真的生效,区间外的订单不会出现在文件里")
    void timeRangeIsApplied() throws Exception {
        String tomorrow = LocalDateTime.now().plusDays(1).withNano(0).toString();

        Response response = export("?orderNo=" + orderNo + "&startTime=" + tomorrow, login());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).as("只剩表头一行:%s", response.body())
                .doesNotContain(orderNo).doesNotContain("导出用例收货人");
    }

    @Test
    @DisplayName("导出要登录:未登录时 401,而不是默默导出一份全店订单")
    void exportRequiresLogin() throws Exception {
        assertThat(export("?orderNo=" + orderNo, null).status()).isEqualTo(401);
    }

    // ——— 辅助 ———

    private Response export(String queryString, String token) throws Exception {
        return get("/mall/admin/orders/export" + queryString, token);
    }

    private String login() throws Exception {
        String body = "{\"tenantCode\":\"platform\",\"username\":\"" + username
                + "\",\"password\":\"" + ADMIN_PASSWORD + "\",\"deviceId\":\"order-export-it\"}";
        Response response = send(HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), "/auth/login", null);
        Matcher matcher = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.body());
        assertThat(matcher.find()).as("登录失败:%s", response.body()).isTrue();
        return matcher.group(1);
    }

    private Response get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder().GET(), path, token);
    }

    private Response send(HttpRequest.Builder builder, String path, String token) throws Exception {
        builder.uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""),
                response.headers().firstValue("Content-Disposition").orElse(""),
                new String(response.body(), StandardCharsets.UTF_8));
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, PLATFORM_ADMIN_USER_ID, "127.0.0.1", "order-export-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private record Response(int status, String contentType, String contentDisposition, String body) {
    }
}
