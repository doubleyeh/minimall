package com.minimall.contract;

import com.minimall.sys.api.dto.UserSaveRequest;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.repository.SysUserRepository;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.service.UserService;
import com.minimall.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商城管理端的正向 CRUD 环(真 HTTP、真库)。
 *
 * <p>与 {@code SysApiCrudHttpIntegrationTest} 同构,针对的是 {@code /mall/admin/**}。
 * 小程序端的主链路(登录 → 加购 → 下单 → 支付回调 → 申请售后)已有 {@code MallHttpFlowIntegrationTest} 覆盖,
 * 但**商家侧**的配置类接口此前只有权限矩阵的空请求探过一遍 —— 请求体不合法时校验阶段就 400 了,
 * 控制器方法一行都没执行,所以覆盖率一直卡在 40% 上下。
 *
 * <p>这里按"商家配置商品卖东西"的真实顺序走:分类 → 商品 → 运费模板 → 满减 → 优惠券。
 * 顺序本身就是一类约束:必须先有分类才能建商品,先有商品才能用运费模板与活动覆盖它。
 *
 * <p>用平台超管令牌是为了绕开权限码(4.10),这跟 {@code SysApiCrudHttpIntegrationTest} 同一个理由。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MallAdminApiCrudHttpIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final String PLATFORM_TENANT_CODE = "platform";
    private static final long SEED_ADMIN_ID = 1L;
    private static final String ADMIN_PASSWORD = "MallAdmin@123456";
    /** 活动与券的有效期:项目里 LocalDateTime 走 ISO 格式,不是配置里的 date-format。 */
    private static final String VALID_FROM = "2020-01-01T00:00:00";
    private static final String VALID_TO = "2035-01-01T00:00:00";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private UserService userService;
    @Autowired
    private StringRedisTemplate redis;

    private String adminUsername;
    private String token;

    @BeforeEach
    void setUpSuperAdmin() throws Exception {
        redis.delete(redis.keys("*127.0.0.1*"));
        adminUsername = "malladmin" + suffix();
        asSuperUser(() -> {
            userService.create(new UserSaveRequest(adminUsername, ADMIN_PASSWORD, "商城用例超管",
                    null, null, List.of(), 1));
            SysUser superUser = userRepository.findByTenantIdAndUsername(PLATFORM_TENANT_ID, adminUsername)
                    .orElseThrow();
            superUser.setIsSuper(1);
            superUser.setMustChangePassword(0);
            userRepository.save(superUser);
            return null;
        });
        token = login();
    }

    // ================================================================ 分类与商品

    @Test
    @DisplayName("分类:新建 → 改名 → 删除")
    void categoryCrudCycle() throws Exception {
        String name = "CRUD 分类" + suffix();
        Response created = post("/mall/admin/categories", categoryBody(name));
        assertThat(created.code()).as("新建分类:%s", created.body()).isEqualTo("0");
        Long categoryId = created.dataAsNumber();
        assertThat(categoryId).isNotNull();

        assertThat(get("/mall/admin/categories/tree", token).body()).contains(name);

        assertThat(put("/mall/admin/categories/" + categoryId, categoryBody(name + "改")).code()).isEqualTo("0");
        assertThat(get("/mall/admin/categories/tree", token).body()).contains(name + "改");

        assertThat(delete("/mall/admin/categories/" + categoryId).code()).isEqualTo("0");
    }

    @Test
    @DisplayName("商品:建分类 → 新建商品 → 改价改图 → 上下架 → 删除")
    void goodsCrudCycle() throws Exception {
        Long categoryId = post("/mall/admin/categories", categoryBody("商品分类" + suffix())).dataAsNumber();

        String goodsName = "CRUD 商品" + suffix();
        Response created = post("/mall/admin/goods", goodsBody(categoryId, goodsName, 0, "SKU-A" + suffix(), "99.00"));
        assertThat(created.code()).as("新建商品:%s", created.body()).isEqualTo("0");
        Long goodsId = created.dataAsNumber();
        assertThat(goodsId).isNotNull();

        assertThat(get("/mall/admin/goods?pageNo=1&pageSize=50", token).body()).contains(goodsName);

        // 改价改名。提交里带的是**新** SKU:旧 SKU 会被停售而不是删除(历史订单要能回查)
        assertThat(put("/mall/admin/goods/" + goodsId,
                goodsBody(categoryId, goodsName + "改", 1, "SKU-B" + suffix(), "88.00")).code()).isEqualTo("0");
        Response detail = get("/mall/admin/goods/" + goodsId, token);
        assertThat(detail.body()).contains(goodsName + "改").contains("88.00");

        // 上架要求有可售库存,所以必须先有启用的 SKU(这里刚好有)
        assertThat(put("/mall/admin/goods/" + goodsId + "/status?status=1", null).code())
                .as("状态走 query 参数").isEqualTo("0");
        assertThat(put("/mall/admin/goods/" + goodsId + "/status?status=0", null).code()).isEqualTo("0");

        assertThat(delete("/mall/admin/goods/" + goodsId).code()).isEqualTo("0");
    }

    @Test
    @DisplayName("商品:缺少必填字段或分类不存在都要被拒")
    void goodsCreateValidates() throws Exception {
        assertThat(post("/mall/admin/goods", "{\"goodsName\":\"没有分类\"}").code())
                .as("参数校验失败按约定是 200 + 40003").isEqualTo(ErrorCode.PARAM_INVALID.code() + "");

        Long categoryId = post("/mall/admin/categories", categoryBody("校验分类" + suffix())).dataAsNumber();
        Response unknownCategory = post("/mall/admin/goods",
                goodsBody(999999L, "分类不存在" + suffix(), 1, "SKU-X" + suffix(), "1.00"));
        assertThat(unknownCategory.code()).as("分类必须存在:%s", unknownCategory.body()).isNotEqualTo("0");
        assertThat(categoryId).isNotNull();
    }

    // ================================================================ 运费模板

    @Test
    @DisplayName("运费模板:新建 → 改名改规则 → 删除")
    void freightTemplateCrudCycle() throws Exception {
        String name = "CRUD 模板" + suffix();
        Response created = post("/mall/admin/freight-templates", freightBody(name, "10.00", "2.00"));
        assertThat(created.code()).as("新建运费模板:%s", created.body()).isEqualTo("0");
        Long templateId = created.dataAsNumber();

        assertThat(get("/mall/admin/freight-templates", token).body()).contains(name);

        assertThat(put("/mall/admin/freight-templates/" + templateId,
                freightBody(name + "改", "12.00", "3.00")).code()).isEqualTo("0");
        assertThat(get("/mall/admin/freight-templates/" + templateId, token).body()).contains(name + "改");

        assertThat(delete("/mall/admin/freight-templates/" + templateId).code()).isEqualTo("0");
    }

    @Test
    @DisplayName("运费模板:没有 ALL 兜底规则会被拒(否则部分省份算不出运费)")
    void freightTemplateRequiresFallbackRule() throws Exception {
        Response noFallback = post("/mall/admin/freight-templates",
                "{\"templateName\":\"CRUD 无兜底" + suffix() + "\",\"chargeType\":1,\"rules\":["
                        + ruleBody("广东省") + "]}");
        assertThat(noFallback.code()).as("响应=%s", noFallback.body()).isEqualTo(ErrorCode.PARAM_INVALID.code() + "");
    }

    // ================================================================ 营销

    @Test
    @DisplayName("满减:新建 → 改名 → 停用")
    void promotionCrudCycle() throws Exception {
        String name = "CRUD 满减" + suffix();
        Response created = post("/mall/admin/promotions", promotionBody(name));
        assertThat(created.code()).as("新建满减:%s", created.body()).isEqualTo("0");
        Long activityId = created.dataAsNumber();

        assertThat(get("/mall/admin/promotions?pageNo=1&pageSize=50", token).body()).contains(name);
        assertThat(put("/mall/admin/promotions/" + activityId, promotionBody(name + "改")).code()).isEqualTo("0");
        assertThat(put("/mall/admin/promotions/" + activityId + "/status?status=0", null).code()).isEqualTo("0");
    }

    @Test
    @DisplayName("满减:阶梯规则不是合法 JSON 数组时被拒")
    void promotionValidatesReductionRule() throws Exception {
        assertThat(post("/mall/admin/promotions", "{\"activityName\":\"CRUD 坏规则" + suffix()
                + "\",\"reductionRule\":\"不是JSON\",\"scopeType\":1,\"validStartTime\":\"" + VALID_FROM
                + "\",\"validEndTime\":\"" + VALID_TO + "\",\"status\":1}").code())
                .as("规则写坏了必须挡在保存这一步,否则下单时静默不减免")
                .isNotEqualTo("0");
    }

    @Test
    @DisplayName("优惠券:新建 → 改名 → 停用")
    void couponCrudCycle() throws Exception {
        String name = "CRUD 券" + suffix();
        Response created = post("/mall/admin/coupons", couponBody(name, "10.00"));
        assertThat(created.code()).as("新建优惠券:%s", created.body()).isEqualTo("0");
        Long couponId = created.dataAsNumber();

        assertThat(get("/mall/admin/coupons?pageNo=1&pageSize=50", token).body()).contains(name);
        assertThat(put("/mall/admin/coupons/" + couponId, couponBody(name + "改", "15.00")).code()).isEqualTo("0");
        assertThat(put("/mall/admin/coupons/" + couponId + "/status?status=0", null).code()).isEqualTo("0");
    }

    @Test
    @DisplayName("优惠券:折扣券的折扣率必须落在 0 与 1 之间")
    void couponValidatesDiscountRate() throws Exception {
        // 折扣券(couponType=2)的 rate 等于 1 是"不打折",大于 1 是加价,都说明配置写错了
        String body = "{\"couponName\":\"CRUD 折扣券" + suffix() + "\",\"couponType\":2,\"discountAmount\":null,"
                + "\"discountRate\":1.5,\"minOrderAmount\":0,\"totalCount\":10,\"perCustomerLimit\":1,"
                + "\"validStartTime\":\"" + VALID_FROM + "\",\"validEndTime\":\"" + VALID_TO + "\",\"status\":1}";
        assertThat(post("/mall/admin/coupons", body).code()).isEqualTo(ErrorCode.PARAM_INVALID.code() + "");
    }

    @Test
    @DisplayName("会员等级:新建 → 改名改折扣")
    void memberLevelCrudCycle() throws Exception {
        String name = "CRUD 等级" + suffix();
        Response created = post("/mall/admin/member-levels",
                "{\"levelName\":\"" + name + "\",\"levelSort\":9,\"growthThreshold\":1000,"
                        + "\"discountRate\":0.95,\"status\":1}");
        assertThat(created.code()).as("新建会员等级:%s", created.body()).isEqualTo("0");
        Long levelId = created.dataAsNumber();

        assertThat(get("/mall/admin/member-levels", token).body()).contains(name);
        assertThat(put("/mall/admin/member-levels/" + levelId,
                "{\"levelName\":\"" + name + "改\",\"levelSort\":8,\"growthThreshold\":2000,"
                        + "\"discountRate\":0.9,\"status\":1}").code()).isEqualTo("0");
    }

    // ================================================================ 请求构造

    private String categoryBody(String name) {
        return "{\"parentId\":null,\"categoryName\":\"" + name + "\",\"icon\":null,\"sortOrder\":1,\"status\":1}";
    }

    private String ruleBody(String region) {
        return "{\"region\":\"" + region + "\",\"firstUnit\":1,\"firstFee\":10.00,\"additionalUnit\":1,"
                + "\"additionalFee\":2.00,\"freeShippingAmount\":null}";
    }

    private String freightBody(String name, String firstFee, String additionalFee) {
        return "{\"templateName\":\"" + name + "\",\"chargeType\":1,\"rules\":["
                + "{\"region\":\"ALL\",\"firstUnit\":1,\"firstFee\":" + firstFee + ",\"additionalUnit\":1,"
                + "\"additionalFee\":" + additionalFee + ",\"freeShippingAmount\":null}]}";
    }

    private String goodsBody(Long categoryId, String goodsName, Integer status, String skuCode, String price) {
        return "{\"categoryId\":" + categoryId + ",\"goodsName\":\"" + goodsName + "\",\"goodsSubtitle\":\"副标题\","
                + "\"mainImage\":\"https://example.com/main.png\",\"detailContent\":\"<p>详情</p>\","
                + "\"freightTemplateId\":null,\"sortOrder\":1,\"status\":" + status
                + ",\"images\":[\"https://example.com/1.png\"],\"specs\":[],\"skus\":["
                + "{\"id\":null,\"skuCode\":\"" + skuCode + "\",\"skuName\":\"默认规格\",\"skuImage\":null,"
                + "\"price\":" + price + ",\"costPrice\":null,\"stock\":50,\"weight\":null,\"status\":1,"
                + "\"specValues\":[]}]}";
    }

    private String promotionBody(String name) {
        return "{\"activityName\":\"" + name + "\",\"reductionRule\":\"[{\\\"amount\\\":100,\\\"reduce\\\":10}]\","
                + "\"scopeType\":1,\"scopeIds\":null,\"validStartTime\":\"" + VALID_FROM
                + "\",\"validEndTime\":\"" + VALID_TO + "\",\"status\":1}";
    }

    private String couponBody(String name, String discountAmount) {
        return "{\"couponName\":\"" + name + "\",\"couponType\":1,\"discountAmount\":" + discountAmount
                + ",\"discountRate\":null,\"minOrderAmount\":100,\"totalCount\":100,\"perCustomerLimit\":1,"
                + "\"validStartTime\":\"" + VALID_FROM + "\",\"validEndTime\":\"" + VALID_TO + "\",\"status\":1}";
    }

    private String login() throws Exception {
        Response login = post("/auth/login", "{\"tenantCode\":\"" + PLATFORM_TENANT_CODE + "\",\"username\":\""
                + adminUsername + "\",\"password\":\"" + ADMIN_PASSWORD + "\",\"deviceId\":\"mall-crud-it\"}");
        assertThat(login.code()).as("登录必须成功:响应=%s", login.body()).isEqualTo("0");
        return login.text("token");
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, SEED_ADMIN_ID, "127.0.0.1", "mall-crud-it"));
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

    private Response put(String path, String jsonBody) throws Exception {
        return send(HttpRequest.newBuilder().PUT(body(jsonBody)), path, token);
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
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

        /** 新建类接口把主键放在 data 上:{"code":0,"data":123}。 */
        Long dataAsNumber() {
            Matcher matcher = Pattern.compile("\"data\":(\\d+)").matcher(body);
            return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
        }
    }
}
