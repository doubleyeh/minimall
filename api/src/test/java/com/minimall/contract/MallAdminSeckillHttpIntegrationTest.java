package com.minimall.contract;

import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallSeckillActivityRepository;
import com.minimall.mall.domain.repository.MallSeckillBuyerRepository;
import com.minimall.mall.domain.repository.MallSeckillItemRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.service.support.SeckillRedisGate;
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
 * 秒杀管理端接口(商城设计文档 3.12):草稿 → 发布(冻结) → 取消(解冻) → 删除。
 *
 * <p>走真 HTTP 而不是直接调 service:发布/取消挂的是不同的权限码,单测绕不过鉴权与参数绑定的坑。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MallAdminSeckillHttpIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final String PLATFORM_TENANT_CODE = "platform";
    private static final long SEED_ADMIN_ID = 1L;
    private static final String ADMIN_PASSWORD = "SeckillAdmin@123456";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private MallGoodsRepository goodsRepository;
    @Autowired
    private MallSkuRepository skuRepository;
    @Autowired
    private MallSeckillActivityRepository activityRepository;
    @Autowired
    private MallSeckillItemRepository seckillItemRepository;
    @Autowired
    private MallSeckillBuyerRepository buyerRepository;
    @Autowired
    private SeckillRedisGate gate;

    private String adminUsername;
    private String token;
    private Long goodsId;
    private Long skuId;

    @BeforeEach
    void setUp() throws Exception {
        redis.delete(redis.keys("*127.0.0.1*"));
        adminUsername = "seckilladmin" + suffix();
        asSuperUser(() -> {
            userService.create(new UserSaveRequest(adminUsername, ADMIN_PASSWORD, "秒杀用例超管",
                    null, null, List.of(), 1));
            SysUser superUser = userRepository.findByTenantIdAndUsername(PLATFORM_TENANT_ID, adminUsername)
                    .orElseThrow();
            superUser.setIsSuper(1);
            superUser.setMustChangePassword(0);
            userRepository.save(superUser);

            MallGoods goods = new MallGoods();
            goods.setCategoryId(0L);
            goods.setGoodsName("秒杀接口商品");
            goods.setMainImage("https://example.com/http-seckill.png");
            goods.setSalePriceMin(new BigDecimal("50.00"));
            goods.setSalePriceMax(new BigDecimal("50.00"));
            goods.setTotalStock(10);
            goods.setSaleCount(0);
            goods.setStatus(1);
            goods.setSortOrder(0);
            goodsId = goodsRepository.save(goods).getId();

            MallSku sku = new MallSku();
            sku.setGoodsId(goodsId);
            sku.setSkuCode("HS-" + suffix());
            sku.setSkuName("默认规格");
            sku.setPrice(new BigDecimal("50.00"));
            sku.setStock(10);
            sku.setLockedStock(0);
            sku.setStatus(1);
            skuId = skuRepository.save(sku).getId();
            return null;
        });
        token = login();
    }

    @AfterEach
    void cleanUp() {
        asSuperUser(() -> {
            seckillItemRepository.findAll().forEach(item -> gate.clear(item.getId()));
            buyerRepository.deleteAll(buyerRepository.findAll());
            seckillItemRepository.deleteAll(seckillItemRepository.findAll());
            activityRepository.deleteAll(activityRepository.findAll());
            if (skuId != null) {
                skuRepository.findById(skuId).ifPresent(skuRepository::delete);
            }
            if (goodsId != null) {
                goodsRepository.findById(goodsId).ifPresent(goodsRepository::delete);
            }
            userRepository.findByTenantIdAndUsername(PLATFORM_TENANT_ID, adminUsername)
                    .ifPresent(userRepository::delete);
            return null;
        });
    }

    @Test
    @DisplayName("草稿 → 发布冻结 → 取消解冻 → 删除,接口与库存联动正确")
    void crudAndFreezeFlow() throws Exception {
        String body = """
                {"activityName":"接口秒杀","startTime":"%s","endTime":"%s","sortOrder":0,
                 "items":[{"skuId":"%s","seckillPrice":9.90,"seckillStock":3,"perCustomerLimit":1}]}
                """.formatted(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1), skuId);

        Response created = post("/mall/admin/seckills", body);
        assertThat(created.code()).as(created.body()).isEqualTo("0");
        String activityId = created.id();

        assertThat(get("/mall/admin/seckills?keyword=接口秒杀", token).body()).contains("接口秒杀");
        assertThat(get("/mall/admin/seckills/" + activityId, token).body()).contains("接口秒杀");

        Response published = post("/mall/admin/seckills/" + activityId + "/publish", null);
        assertThat(published.code()).as(published.body()).isEqualTo("0");
        assertThat(published.number("data")).as("返回冻结件数").isEqualTo(3);
        assertThat(asSuperUser(() -> skuRepository.findById(skuId).orElseThrow().getFrozenStock()))
                .isEqualTo(3);

        Response cancelled = post("/mall/admin/seckills/" + activityId + "/cancel", null);
        assertThat(cancelled.code()).as(cancelled.body()).isEqualTo("0");
        assertThat(asSuperUser(() -> skuRepository.findById(skuId).orElseThrow().getFrozenStock()))
                .isZero();

        assertThat(delete("/mall/admin/seckills/" + activityId).code()).isEqualTo("0");
    }

    @Test
    @DisplayName("库存不够冻结时发布失败,状态留在草稿")
    void publishFailsWhenStockInsufficient() throws Exception {
        String body = """
                {"activityName":"超额秒杀","startTime":"%s","endTime":"%s","sortOrder":0,
                 "items":[{"skuId":"%s","seckillPrice":9.90,"seckillStock":99,"perCustomerLimit":1}]}
                """.formatted(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1), skuId);
        String activityId = post("/mall/admin/seckills", body).id();

        Response published = post("/mall/admin/seckills/" + activityId + "/publish", null);

        assertThat(published.code()).isNotEqualTo("0");
        assertThat(asSuperUser(() -> skuRepository.findById(skuId).orElseThrow().getFrozenStock())).isZero();
        assertThat(get("/mall/admin/seckills/" + activityId, token).body()).contains("\"status\":0");
    }

    // ------------------------------------------------------------------ 辅助

    private String login() throws Exception {
        Response login = post("/auth/login", "{\"tenantCode\":\"" + PLATFORM_TENANT_CODE
                + "\",\"username\":\"" + adminUsername + "\",\"password\":\"" + ADMIN_PASSWORD
                + "\",\"deviceId\":\"seckill-it\"}");
        assertThat(login.code()).as("登录失败:%s", login.body()).isEqualTo("0");
        return login.text("token");
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, SEED_ADMIN_ID, "127.0.0.1", "seckill-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private Response get(String path, String accessToken) throws Exception {
        return send(HttpRequest.newBuilder().GET(), path, accessToken);
    }

    private Response post(String path, String jsonBody) throws Exception {
        return send(HttpRequest.newBuilder().POST(body(jsonBody)), path, token);
    }

    private Response delete(String path) throws Exception {
        return send(HttpRequest.newBuilder().DELETE(), path, token);
    }

    private HttpRequest.BodyPublisher body(String jsonBody) {
        return jsonBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
    }

    private Response send(HttpRequest.Builder builder, String path, String accessToken) throws Exception {
        builder.uri(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private record Response(int status, String body) {

        String code() {
            Matcher matcher = Pattern.compile("\"code\":(\\d+)").matcher(body);
            return matcher.find() ? matcher.group(1) : "-1";
        }

        String text(String field) {
            Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        }

        String id() {
            Matcher matcher = Pattern.compile("\"data\":\"?(\\d+)\"?").matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        }

        int number(String field) {
            Matcher matcher = Pattern.compile("\"" + field + "\":(\\d+)").matcher(body);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
        }
    }
}
