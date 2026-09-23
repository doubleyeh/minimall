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

        assertThat(get("/mall/admin/goods?goodsName=" + enc(goodsName) + "&pageSize=50", token).body())
                .as("新建的商品要能在列表里查到").contains(goodsName);

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

    /**
     * 商品保存时对外部引用与 SKU 编码的校验。
     *
     * <p>共同点是:**错误的引用必须在保存这一步被挡住,而不是等到使用时才爆**。运费模板不存在,
     * 商品存得进去但下单时算不出运费;SKU 编码重复,库上的唯一索引确实能兜底,但抛出来的是
     * SQL 异常 —— 前端只会看到"系统异常",而这里提前给 50002 才能提示到具体字段。
     */
    @Test
    @DisplayName("商品:运费模板不存在、SKU 编码重复、编码被别的商品占用,都要在保存时被拒")
    void goodsRejectsBadReferencesAndDuplicateSkuCodes() throws Exception {
        Long categoryId = post("/mall/admin/categories", categoryBody("引用校验分类" + suffix())).dataAsNumber();
        String conflict = String.valueOf(ErrorCode.DATA_CONFLICT.code());
        String notFound = String.valueOf(ErrorCode.NOT_FOUND.code());

        // ① 运费模板指向一个不存在的主键
        Response badTemplate = post("/mall/admin/goods",
                goodsBody(categoryId, "坏模板" + suffix(), 1, "SKU-T" + suffix(), "10.00")
                        .replace("\"freightTemplateId\":null", "\"freightTemplateId\":999999999"));
        assertThat(badTemplate.code())
                .as("运费模板不存在要返回资源不存在,响应=%s", badTemplate.body())
                .isEqualTo(notFound);

        // ② 同一次提交里两个 SKU 用了同一个编码
        String duplicated = "SKU-D" + suffix();
        Response dupInBatch = post("/mall/admin/goods", goodsWithSkus(categoryId, "批内重复" + suffix(),
                skuJson(duplicated, "10.00") + "," + skuJson(duplicated, "20.00")));
        assertThat(dupInBatch.code())
                .as("同一批里 SKU 编码重复要被拒,响应=%s", dupInBatch.body())
                .isEqualTo(conflict);

        // ③ 编码已经被另一个商品占用(@NotEmpty 之外还要查库,库上有唯一索引兜底)
        String taken = "SKU-TAKEN" + suffix();
        Long firstGoods = post("/mall/admin/goods",
                goodsWithSkus(categoryId, "先占编码" + suffix(), skuJson(taken, "10.00"))).dataAsNumber();
        assertThat(firstGoods).as("建第一个商品").isNotNull();
        Response reused = post("/mall/admin/goods",
                goodsWithSkus(categoryId, "复用编码" + suffix(), skuJson(taken, "10.00")));
        assertThat(reused.code())
                .as("SKU 编码被其他商品占用要被拒,响应=%s", reused.body())
                .isEqualTo(conflict);
    }

    /**
     * 商品保存里"提交内容有缺陷"的那几条分支。
     *
     * <p>它们不是参数校验(注解看不见),也不是主流程,但每条都对应一个真实的数据清理规则 ——
     * 缺省字段要保留原值、空白轮播图不该入库、null 规格值要挡住、改自己的 SKU 编码不算被占用。
     * 这些分支<b>只能从 HTTP 打进来</b>:它们靠的是 JSON 数组里夹 null 这类输入,
     * 服务层用例构造请求对象时反而绕不过去。
     */
    @Test
    @DisplayName("商品:缺省字段保留原值、空白图片跳过、null 规格值被拒、改自己的 SKU 编码不算占用")
    void goodsDefensiveBranches() throws Exception {
        Long categoryId = post("/mall/admin/categories", categoryBody("边界分类" + suffix())).dataAsNumber();
        String goodsName = "CRUD 边界商品" + suffix();
        String skuCode = "SKU-BD" + suffix();

        // ① 不传 sortOrder / status / specs,且轮播图数组里夹了 null 与空白项。
        //    sortOrder 这条曾经是坏的:实体上是 @Column(nullable = false)、DTO 上却是可选,
        //    而 applyBasicFields 只在"传了才设置" → 不传就把 NULL 发给库,撞非空约束后
        //    被兜底成 50002「数据已存在或存在引用关系」,与真实原因完全对不上
        Response created = post("/mall/admin/goods",
                "{\"categoryId\":" + categoryId + ",\"goodsName\":\"" + goodsName + "\","
                        + "\"mainImage\":\"https://example.com/main.png\","
                        + "\"images\":[\"https://example.com/1.png\",null,\"  \"],"
                        + "\"skus\":[" + skuJson(skuCode, "10.00") + "]}");
        assertThat(created.code())
                .as("可选字段缺失不该报错(库上都是 NOT NULL,缺省值必须由服务层补):%s", created.body())
                .isEqualTo("0");
        Long goodsId = created.dataAsNumber();
        assertThat(goodsId).as("建商品").isNotNull();

        Response detail = get("/mall/admin/goods/" + goodsId, token);
        assertThat(detail.body()).as("不传 status 时新建商品默认下架").contains("\"status\":0");
        assertThat(detail.body()).as("不传 sortOrder 时缺省为 0").contains("\"sortOrder\":0");
        assertThat(detail.body())
                .as("null 与空白的轮播图要跳过,只留真实的那一条 —— 否则商品页会出现空图位")
                .contains("\"images\":[\"https://example.com/1.png\"]");
        Long skuId = firstSkuId(detail.body());

        // ② 空白关键词不当事:与分类组合后仍能查到它(单靠空白词会退化成"返回全量",断不准)
        assertThat(get("/mall/admin/goods?goodsName=%20%20&categoryId=" + categoryId + "&pageSize=50", token).body())
                .as("全空白的关键词必须按「没有关键词」处理").contains(goodsName);

        // ③ 上架后原样提交:带自己的 SKU id 与编码、省略 status。
        //    一箭三雕 —— 缺省 status 要保留原值、判重时要排除自己(否则改个价都会被判编码冲突)、
        //    而且这里只能用**自己的 id 原样提交**:若换成 id=null 的同编码 SKU,
        //    库上的 uk_tenant_sku_code 会真的撞唯一键(旧 SKU 只停售不删除)
        assertThat(put("/mall/admin/goods/" + goodsId + "/status?status=1", null).code()).isEqualTo("0");
        assertThat(put("/mall/admin/goods/" + goodsId,
                "{\"categoryId\":" + categoryId + ",\"goodsName\":\"" + goodsName + "\","
                        + "\"mainImage\":\"https://example.com/main.png\","
                        + "\"skus\":[{\"id\":" + skuId + ",\"skuCode\":\"" + skuCode + "\","
                        + "\"skuName\":\"默认规格\",\"price\":12.00,\"stock\":50,\"specValues\":[]}]}").code())
                .as("保留自己的 SKU 与编码、省略 status 都该成功(编码判重必须排除自己)")
                .isEqualTo("0");
        Response afterUpdate = get("/mall/admin/goods/" + goodsId, token);
        assertThat(afterUpdate.body())
                .as("提交里没有 status 时应当保留原来的上架状态,不能被悄悄改成下架")
                .contains("\"status\":1");
        assertThat(afterUpdate.body()).as("改价生效").contains("12.00");

        // ④ 规格值数组里夹了 null:计数时 null 被过滤掉,于是"长度对不上"按重复处理并拒绝。
        //    报错文案说的是"重复的规格值",而真实原因是夹了空项 —— 这里只钉住"被拒",不钉文案
        assertThat(post("/mall/admin/goods",
                "{\"categoryId\":" + categoryId + ",\"goodsName\":\"空规格值" + suffix() + "\","
                        + "\"mainImage\":\"https://example.com/main.png\","
                        + "\"specs\":[{\"specName\":\"颜色\",\"values\":[null,\"红\"]}],"
                        + "\"skus\":[" + skuJson("SKU-NV" + suffix(), "10.00") + "]}").code())
                .as("规格值里夹 null 必须被拒,否则会建出一条没有名字的规格值")
                .isEqualTo(ErrorCode.PARAM_INVALID.code() + "");

        // ⑤ SKU 引用了一个 null 规格值:关联建不起来,必须挡在保存这一步
        assertThat(post("/mall/admin/goods",
                "{\"categoryId\":" + categoryId + ",\"goodsName\":\"空引用" + suffix() + "\","
                        + "\"mainImage\":\"https://example.com/main.png\","
                        + "\"specs\":[{\"specName\":\"颜色\",\"values\":[\"红\"]}],"
                        + "\"skus\":[{\"id\":null,\"skuCode\":\"SKU-NR" + suffix() + "\",\"skuName\":\"红\","
                        + "\"price\":10.00,\"stock\":1,\"specValues\":[null]}]}").code())
                .as("SKU 的规格值必须是本次提交里的,空值同样要拒")
                .isEqualTo(ErrorCode.PARAM_INVALID.code() + "");

        // ⑥ 更新时提交了**别的商品**的 SKU 编码:要在服务层提前挡住,并给出能定位字段的文案。
        //    光断言 50002 是不够的 —— 库上的 uk_tenant_sku_code 也会抛同一个码,
        //    所以这里必须断言那句话,才能证明是守卫先命中、而不是让 SQL 异常漏出去
        String takenCode = "SKU-TK" + suffix();
        assertThat(post("/mall/admin/goods",
                goodsWithSkus(categoryId, "占用方" + suffix(), skuJson(takenCode, "10.00"))).code()).isEqualTo("0");
        Response stolen = put("/mall/admin/goods/" + goodsId,
                "{\"categoryId\":" + categoryId + ",\"goodsName\":\"" + goodsName + "\","
                        + "\"mainImage\":\"https://example.com/main.png\","
                        + "\"skus\":[{\"id\":" + skuId + ",\"skuCode\":\"" + takenCode + "\","
                        + "\"skuName\":\"默认规格\",\"price\":12.00,\"stock\":50,\"specValues\":[]}]}");
        assertThat(stolen.code())
                .as("占用别的商品的 SKU 编码要被拒,响应=%s", stolen.body())
                .isEqualTo(ErrorCode.DATA_CONFLICT.code() + "");
        assertThat(stolen.body())
                .as("必须是服务层守卫给出的可读文案,而不是数据库唯一键的「数据已存在或存在引用关系」")
                .contains("已被其他商品使用");
    }

    /** 从商品详情的 skus 数组里取第一个 SKU 的 id。 */
    private Long firstSkuId(String detailBody) {
        Matcher matcher = Pattern.compile("\"skus\":\\[\\{\"id\":\"?(\\d+)\"?").matcher(detailBody);
        assertThat(matcher.find()).as("详情里应当能取到 SKU id,响应=%s", detailBody).isTrue();
        return Long.valueOf(matcher.group(1));
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

        assertThat(get("/mall/admin/promotions?activityName=" + enc(name) + "&pageSize=50", token).body())
                .as("新建的活动要能在列表里查到").contains(name);
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

        assertThat(get("/mall/admin/coupons?couponName=" + enc(name) + "&pageSize=50", token).body())
                .as("新建的券要能在列表里查到").contains(name);
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

    @Test
    @DisplayName("优惠券:状态只能是 0 或 1,传别的值要被拒")
    void couponChangeStatusRejectsBadValue() throws Exception {
        Long couponId = post("/mall/admin/coupons", couponBody("状态校验券" + suffix(), "10.00")).dataAsNumber();
        assertThat(couponId).as("建券").isNotNull();

        Response bad = put("/mall/admin/coupons/" + couponId + "/status?status=2", null);
        assertThat(bad.code())
                .as("状态只能是 0(停用)或 1(启用),响应=%s", bad.body())
                .isEqualTo(String.valueOf(ErrorCode.PARAM_INVALID.code()));
    }

    /**
     * 券的两个配置校验。
     *
     * <p>有效期倒置是最常见的人工笔误(起止时间填反),放行的话这张券会永远处于"未开始/已结束",
     * 运营在列表里看到它却领不了,排查成本很高。减免金额为 0 或负则是"发一张没有用的券",
     * 更糟的是负数会把订单金额往上加。
     */
    @Test
    @DisplayName("优惠券:有效期倒置、减免金额非正都要被拒")
    void couponRejectsInvertedPeriodAndNonPositiveAmount() throws Exception {
        String paramInvalid = String.valueOf(ErrorCode.PARAM_INVALID.code());

        Response inverted = post("/mall/admin/coupons",
                "{\"couponName\":\"CRUD 倒置券" + suffix() + "\",\"couponType\":1,\"discountAmount\":10.00,"
                        + "\"discountRate\":null,\"minOrderAmount\":0,\"totalCount\":10,\"perCustomerLimit\":1,"
                        + "\"validStartTime\":\"" + VALID_TO + "\",\"validEndTime\":\"" + VALID_FROM
                        + "\",\"status\":1}");
        assertThat(inverted.code())
                .as("结束时间早于开始时间要被拒,响应=%s", inverted.body())
                .isEqualTo(paramInvalid);

        Response zeroAmount = post("/mall/admin/coupons",
                "{\"couponName\":\"CRUD 零减免" + suffix() + "\",\"couponType\":1,\"discountAmount\":0,"
                        + "\"discountRate\":null,\"minOrderAmount\":0,\"totalCount\":10,\"perCustomerLimit\":1,"
                        + "\"validStartTime\":\"" + VALID_FROM + "\",\"validEndTime\":\"" + VALID_TO
                        + "\",\"status\":1}");
        assertThat(zeroAmount.code())
                .as("减免金额必须大于 0,响应=%s", zeroAmount.body())
                .isEqualTo(paramInvalid);
    }

    // ================================================================ 列表筛选

    /**
     * 三个后台列表的筛选参数。
     *
     * <p>此前这些列表只有"能返回"的断言,筛选谓词是不是真的生效从没验过 —— 而谓词写漏**不会报错**,
     * 只会让运营筛任何条件都看到全量数据,页面上看不出来。所以每条都做"命中 / 不命中"两侧断言,
     * 单侧断言对谓词写漏是绿的。名称类字段在实现里是 LIKE(contains),"不命中"那侧必须用
     * 与原名无包含关系的值,否则会因为包含关系反而命中。
     */
    @Test
    @DisplayName("商品列表:按名称、分类、状态筛选要真的生效")
    void goodsListFiltering() throws Exception {
        Long categoryId = post("/mall/admin/categories", categoryBody("筛选分类" + suffix())).dataAsNumber();
        String goodsName = "CRUD 筛选商品" + suffix();
        Long goodsId = post("/mall/admin/goods", goodsBody(categoryId, goodsName, 1, "SKU-F" + suffix(), "10.00"))
                .dataAsNumber();
        assertThat(goodsId).as("建商品").isNotNull();

        assertThat(get("/mall/admin/goods?goodsName=" + enc(goodsName) + "&pageSize=50", token).body())
                .as("按名称筛选要命中").contains("\"id\":\"" + goodsId + "\"");
        assertThat(get("/mall/admin/goods?goodsName=" + enc("绝无此商品" + suffix()) + "&pageSize=50", token).body())
                .as("按不存在的名称筛选不该命中").doesNotContain("\"id\":\"" + goodsId + "\"");

        assertThat(get("/mall/admin/goods?categoryId=" + categoryId + "&pageSize=50", token).body())
                .as("按分类筛选要命中").contains("\"id\":\"" + goodsId + "\"");
        assertThat(get("/mall/admin/goods?categoryId=999999&pageSize=50", token).body())
                .as("按不存在的分类不该命中").doesNotContain("\"id\":\"" + goodsId + "\"");

        // 二级分类下的商品:点一级分类要能连带查出来(与客户端商品列表同一套口径)
        Long childCategoryId = post("/mall/admin/categories",
                "{\"parentId\":" + categoryId + ",\"categoryName\":\"筛选子分类" + suffix()
                        + "\",\"icon\":null,\"sortOrder\":1,\"status\":1}").dataAsNumber();
        assertThat(childCategoryId).as("建子分类").isNotNull();
        Long childGoodsId = post("/mall/admin/goods",
                goodsBody(childCategoryId, "CRUD 子分类商品" + suffix(), 1, "SKU-FC" + suffix(), "20.00"))
                .dataAsNumber();
        assertThat(get("/mall/admin/goods?categoryId=" + categoryId + "&pageSize=50", token).body())
                .as("按一级分类要连带查出子分类的商品").contains("\"id\":\"" + childGoodsId + "\"");
        assertThat(get("/mall/admin/goods?categoryId=" + childCategoryId + "&pageSize=50", token).body())
                .as("子分类不该反查出父分类的商品").doesNotContain("\"id\":\"" + goodsId + "\"");

        assertThat(get("/mall/admin/goods?goodsName=" + enc(goodsName) + "&status=1&pageSize=50", token).body())
                .as("上架状态下要能命中").contains("\"id\":\"" + goodsId + "\"");
        assertThat(put("/mall/admin/goods/" + goodsId + "/status?status=0", null).code()).isEqualTo("0");
        assertThat(get("/mall/admin/goods?goodsName=" + enc(goodsName) + "&status=1&pageSize=50", token).body())
                .as("下架后不该再出现在上架列表里").doesNotContain("\"id\":\"" + goodsId + "\"");
        assertThat(get("/mall/admin/goods?goodsName=" + enc(goodsName) + "&status=0&pageSize=50", token).body())
                .as("下架后应当出现在下架列表里").contains("\"id\":\"" + goodsId + "\"");
    }

    @Test
    @DisplayName("优惠券列表:按名称与状态筛选要真的生效")
    void couponListFiltering() throws Exception {
        String name = "CRUD 筛选券" + suffix();
        Long couponId = post("/mall/admin/coupons", couponBody(name, "10.00")).dataAsNumber();
        assertThat(couponId).as("建券").isNotNull();

        assertThat(get("/mall/admin/coupons?couponName=" + enc(name) + "&pageSize=50", token).body())
                .as("按名称筛选要命中").contains(name);
        assertThat(get("/mall/admin/coupons?couponName=" + enc("绝无此券" + suffix()) + "&pageSize=50", token).body())
                .as("按不存在的名称筛选不该命中").doesNotContain(name);
        assertThat(get("/mall/admin/coupons?couponName=" + enc(name) + "&status=1&pageSize=50", token).body())
                .as("新建的券默认启用").contains(name);

        assertThat(put("/mall/admin/coupons/" + couponId + "/status?status=0", null).code()).isEqualTo("0");
        assertThat(get("/mall/admin/coupons?couponName=" + enc(name) + "&status=1&pageSize=50", token).body())
                .as("停用后不该再出现在启用列表里").doesNotContain(name);
        assertThat(get("/mall/admin/coupons?couponName=" + enc(name) + "&status=0&pageSize=50", token).body())
                .as("停用后应当出现在停用列表里").contains(name);
    }

    @Test
    @DisplayName("满减列表:按活动名与状态筛选要真的生效")
    void promotionListFiltering() throws Exception {
        String name = "CRUD 筛选满减" + suffix();
        Long activityId = post("/mall/admin/promotions", promotionBody(name)).dataAsNumber();
        assertThat(activityId).as("建满减活动").isNotNull();

        assertThat(get("/mall/admin/promotions?activityName=" + enc(name) + "&pageSize=50", token).body())
                .as("按活动名筛选要命中").contains(name);
        assertThat(get("/mall/admin/promotions?activityName=" + enc("绝无此活动" + suffix()) + "&pageSize=50", token)
                .body())
                .as("按不存在的活动名筛选不该命中").doesNotContain(name);
        assertThat(get("/mall/admin/promotions?activityName=" + enc(name) + "&status=1&pageSize=50", token).body())
                .as("新建的活动默认启用").contains(name);

        assertThat(put("/mall/admin/promotions/" + activityId + "/status?status=0", null).code()).isEqualTo("0");
        assertThat(get("/mall/admin/promotions?activityName=" + enc(name) + "&status=1&pageSize=50", token).body())
                .as("停用后不该再出现在启用列表里").doesNotContain(name);
        assertThat(get("/mall/admin/promotions?activityName=" + enc(name) + "&status=0&pageSize=50", token).body())
                .as("停用后应当出现在停用列表里").contains(name);
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

    /** 只带 SKU 列表的商品请求体:编码冲突类用例不需要其余字段。 */
    private String goodsWithSkus(Long categoryId, String goodsName, String skusJson) {
        return "{\"categoryId\":" + categoryId + ",\"goodsName\":\"" + goodsName + "\","
                + "\"goodsSubtitle\":\"副标题\",\"mainImage\":\"https://example.com/main.png\","
                + "\"detailContent\":\"<p>详情</p>\",\"freightTemplateId\":null,\"sortOrder\":1,\"status\":1,"
                + "\"images\":[],\"specs\":[],\"skus\":[" + skusJson + "]}";
    }

    private String skuJson(String skuCode, String price) {
        return "{\"id\":null,\"skuCode\":\"" + skuCode + "\",\"skuName\":\"默认规格\",\"skuImage\":null,"
                + "\"price\":" + price + ",\"costPrice\":null,\"stock\":50,\"weight\":null,\"status\":1,"
                + "\"specValues\":[]}";
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

    /** 查询参数的值要编码:中文与空格都不能直接进 URI。 */
    private String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
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

        /** 新建类接口把主键放在 data 上:{"code":0,"data":123}。id 序列化成字符串后要带引号。 */
        Long dataAsNumber() {
            Matcher matcher = Pattern.compile("\"data\":\"?(\\d+)\"?").matcher(body);
            return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
        }
    }
}
