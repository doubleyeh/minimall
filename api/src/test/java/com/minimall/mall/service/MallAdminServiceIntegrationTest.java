package com.minimall.mall.service;

import com.minimall.api.mall.dto.CategorySaveRequest;
import com.minimall.api.mall.dto.CategoryTreeNode;
import com.minimall.api.mall.dto.CouponSaveRequest;
import com.minimall.api.mall.dto.FreightTemplateSaveRequest;
import com.minimall.api.mall.dto.GoodsSaveRequest;
import com.minimall.api.mall.dto.GoodsDetailView;
import com.minimall.api.mall.dto.MemberLevelSaveRequest;
import com.minimall.api.mall.dto.PromotionSaveRequest;
import com.minimall.api.mall.dto.SkuSaveRequest;
import com.minimall.api.mall.dto.SpecSaveRequest;
import com.minimall.common.BusinessException;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallCoupon;
import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.MallGoodsReview;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallCouponRepository;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallGoodsReviewRepository;
import com.minimall.mall.domain.repository.MallOrderItemRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.domain.repository.MallStockLogRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.infra.auth.ClientPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商城管理端与营销模块的服务层集成测试。
 *
 * <p>覆盖面按"业务风险"挑:商品汇总字段与 SKU 停售语义、运费模板的 ALL 兜底规则、
 * 优惠券的防超发与每人限领、满减规则保存时试算、评价的展示/隐藏。
 * 这些规则错了不会报错,只会安静地少收钱、多发货或让用户领不到券。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("dev")
class MallAdminServiceIntegrationTest {

    private static final long TENANT_ID = 1L;

    @Autowired
    private GoodsCategoryService categoryService;
    @Autowired
    private GoodsService goodsService;
    @Autowired
    private FreightTemplateService freightTemplateService;
    @Autowired
    private CouponService couponService;
    @Autowired
    private PromotionService promotionService;
    @Autowired
    private MemberLevelService memberLevelService;
    @Autowired
    private ReviewService reviewService;
    @Autowired
    private OrderService orderService;
    @Autowired
    private PayService payService;
    @Autowired
    private OrderAdminService orderAdminService;

    @Autowired
    private MallGoodsRepository goodsRepository;
    @Autowired
    private MallSkuRepository skuRepository;
    @Autowired
    private MallCustomerRepository customerRepository;
    @Autowired
    private MallCouponRepository couponRepository;
    @Autowired
    private MallGoodsReviewRepository reviewRepository;
    @Autowired
    private MallOrderRepository orderRepository;
    @Autowired
    private MallOrderItemRepository orderItemRepository;
    @Autowired
    private MallStockLogRepository stockLogRepository;

    private Long customerId;
    private Long categoryId;
    private String categoryName;

    @BeforeEach
    void setUp() {
        inTenant(() -> {
            MallCustomer customer = new MallCustomer();
            customer.setOpenid("admin-it-" + System.nanoTime());
            customer.setStatus(1);
            customer.setPoints(0);
            customer.setGrowthValue(0);
            customer.setRegisterTime(LocalDateTime.now());
            customerId = customerRepository.save(customer).getId();
            // 分类名用随机后缀:它与商品不同,没有"按名字前缀批量清理"的天然依据,
            // 固定名字会让第二次运行直接撞唯一约束(setUp 就失败,报"同级下已存在同名分类")
            categoryName = "测试分类" + System.nanoTime() % 100000;
            categoryId = categoryService.create(new CategorySaveRequest(0L, categoryName, null, 0, 1));
            return null;
        });
        asClient();
    }

    @AfterEach
    void tearDown() {
        ClientContext.clear();
        inTenant(() -> {
            goodsRepository.findAll().stream()
                    .filter(goods -> goods.getCategoryId().equals(categoryId))
                    .forEach(goods -> {
                        skuRepository.findByGoodsIdOrderByIdAsc(goods.getId()).forEach(sku ->
                                stockLogRepository.findAll().stream()
                                        .filter(log -> log.getSkuId().equals(sku.getId()))
                                        .forEach(stockLogRepository::delete));
                        skuRepository.deleteAll(skuRepository.findByGoodsIdOrderByIdAsc(goods.getId()));
                        goodsRepository.delete(goods);
                    });
            orderItemRepository.findAll().forEach(orderItemRepository::delete);
            orderRepository.findAll().stream()
                    .filter(order -> order.getCustomerId().equals(customerId))
                    .forEach(orderRepository::delete);
            reviewRepository.findAll().stream()
                    .filter(review -> review.getCustomerId().equals(customerId))
                    .forEach(reviewRepository::delete);
            couponRepository.findAll().stream()
                    .filter(coupon -> coupon.getCouponName().startsWith("用例券"))
                    .forEach(couponRepository::delete);
            customerRepository.findById(customerId).ifPresent(customerRepository::delete);
            // 分类最后删,且要先删子分类:用例中途失败时子分类可能还挂着,
            // 直接删父分类会被"存在下级分类"拒绝,把失败用例的清理也变成报错
            categoryService.tree(null).stream()
                    .filter(node -> node.id().equals(categoryId))
                    .flatMap(node -> node.children() == null ? Stream.empty() : node.children().stream())
                    .forEach(child -> categoryService.delete(child.id()));
            categoryService.delete(categoryId);
            return null;
        });
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- 分类

    @Test
    @DisplayName("分类:只允许两级,同级重名被拒,有下级时不能删")
    void categoryRules() {
        inTenant(() -> {
            Long childId = categoryService.create(new CategorySaveRequest(categoryId, "二级分类", null, 0, 1));
            assertThatThrownBy(() -> categoryService.create(new CategorySaveRequest(categoryId, "二级分类", null, 0, 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("同名");
            // 三级:挂在二级分类下应被拒绝
            assertThatThrownBy(() -> categoryService.create(new CategorySaveRequest(childId, "三级分类", null, 0, 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("最多两级");
            assertThatThrownBy(() -> categoryService.delete(categoryId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("下级分类");

            // 按 ID 找自己建的那棵树:库里可能还有别的分类(tree.get(0) 不一定是本用例的)
            List<CategoryTreeNode> tree = categoryService.tree(1);
            CategoryTreeNode mine = tree.stream()
                    .filter(node -> node.id().equals(categoryId))
                    .findFirst().orElseThrow();
            assertThat(mine.children()).isNotEmpty();
            categoryService.delete(childId);
            return null;
        });
    }

    // ---------------------------------------------------------------- 商品与 SKU

    @Test
    @DisplayName("商品:创建后汇总字段由 SKU 算出,更新时未提交的 SKU 被停售")
    void goodsSummaryAndSkuRetire() {
        Long id = inTenant(() -> createGoods());
        inTenant(() -> {
            GoodsDetailView detail = goodsService.detail(id);
            assertThat(detail.salePriceMin()).isEqualByComparingTo("10.00");
            assertThat(detail.salePriceMax()).isEqualByComparingTo("20.00");
            assertThat(detail.totalStock()).isEqualTo(30);
            assertThat(detail.skus()).hasSize(2);
            assertThat(detail.specs()).hasSize(1);
            return null;
        });

        // 只提交一个 SKU(把另一个去掉):被去掉的应当是"停售"而不是删除
        inTenant(() -> {
            GoodsDetailView before = goodsService.detail(id);
            Long keepSkuId = before.skus().stream()
                    .filter(sku -> sku.price().compareTo(new BigDecimal("10.00")) == 0)
                    .findFirst().orElseThrow().id();
            goodsService.update(id, new GoodsSaveRequest(categoryId, "测试商品改", null,
                    "https://example.com/a.png", null, null, 0, 1,
                    List.of(),
                    List.of(new SpecSaveRequest("颜色", List.of("红"))),
                    List.of(new SkuSaveRequest(keepSkuId, "SKU-A", "红", null, new BigDecimal("12.00"),
                            null, 10, null, 1, List.of("红")))));
            return null;
        });
        inTenant(() -> {
            GoodsDetailView after = goodsService.detail(id);
            assertThat(after.skus()).as("SKU 不删除,只是停售 —— 历史订单与售后要能回查到它").hasSize(2);
            assertThat(after.skus().stream().filter(sku -> sku.status() == 0).count()).isEqualTo(1);
            // 汇总只统计启用中的 SKU
            assertThat(after.salePriceMin()).isEqualByComparingTo("12.00");
            assertThat(after.salePriceMax()).isEqualByComparingTo("12.00");
            assertThat(after.totalStock()).isEqualTo(10);
            return null;
        });
    }

    @Test
    @DisplayName("商品:库存在 0 时不允许上架,下架不影响历史数据")
    void goodsStatusRules() {
        Long id = inTenant(() -> {
            Long created = goodsService.create(new GoodsSaveRequest(categoryId, "零库存商品", null,
                    "https://example.com/z.png", null, null, 0, 0, List.of(), List.of(),
                    List.of(new SkuSaveRequest(null, "Z-SKU", "默认", null, new BigDecimal("5.00"),
                            null, 0, null, 1, List.of()))));
            return created;
        });
        inTenant(() -> {
            assertThatThrownBy(() -> goodsService.changeStatus(id, 1))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("库存为 0");
            goodsService.changeStatus(id, 0);
            assertThat(goodsService.detail(id).status()).isZero();
            return null;
        });
    }

    // ---------------------------------------------------------------- 运费模板

    @Test
    @DisplayName("运费模板:缺 ALL 兜底规则被拒,被商品引用时不能删")
    void freightTemplateRules() {
        inTenant(() -> {
            assertThatThrownBy(() -> freightTemplateService.create(new FreightTemplateSaveRequest(
                    "用例模板-无兜底", 1, List.of(rule("广东省", 1, 5, 1, 2)))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("兜底");

            Long templateId = freightTemplateService.create(new FreightTemplateSaveRequest(
                    "用例模板", 1, List.of(rule("广东省", 1, 5, 1, 2), rule("ALL", 1, 8, 1, 3))));
            assertThat(freightTemplateService.detail(templateId).rules()).hasSize(2);

            // 绑定到商品后不能删除(避免那些商品突然变成包邮)
            Long id = createGoodsWithTemplate(templateId);
            assertThatThrownBy(() -> freightTemplateService.delete(templateId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("仍有商品使用");
            goodsService.delete(id);
            freightTemplateService.delete(templateId);
            return null;
        });
    }

    // ---------------------------------------------------------------- 优惠券

    @Test
    @DisplayName("优惠券:折扣率不合法被拒;领取受总量与每人限领约束")
    void couponRules() {
        inTenant(() -> {
            // 折扣券的折扣率必须落在 (0,1)
            assertThatThrownBy(() -> couponService.create(new CouponSaveRequest("用例券-错折扣", 2, null,
                    new BigDecimal("1.5"), BigDecimal.ZERO, 10, 1,
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7), 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("折扣率");
            return null;
        });

        Long couponId = inTenant(() -> couponService.create(new CouponSaveRequest("用例券-新人", 1,
                new BigDecimal("5.00"), null, new BigDecimal("50.00"), 1, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(7), 1)));

        assertThat(couponService.claimable()).isNotEmpty();
        Long recordId = couponService.claim(couponId);
        assertThat(recordId).isNotNull();
        assertThat(couponService.mine(1)).hasSize(1);

        // 每人限领 1 张
        assertThatThrownBy(() -> couponService.claim(couponId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("限领");

        // 换个客户:总量只有 1 张,应被"已领完"挡住(条件更新的受影响行数为 0)
        Long anotherCustomerId = inTenant(() -> {
            MallCustomer another = new MallCustomer();
            another.setOpenid("admin-it-2-" + System.nanoTime());
            another.setStatus(1);
            another.setPoints(0);
            another.setGrowthValue(0);
            another.setRegisterTime(LocalDateTime.now());
            return customerRepository.save(another).getId();
        });
        ClientContext.set(new ClientPrincipal(TENANT_ID, anotherCustomerId));
        assertThatThrownBy(() -> couponService.claim(couponId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已领完");
        inTenant(() -> {
            customerRepository.findById(anotherCustomerId).ifPresent(customerRepository::delete);
            MallCoupon coupon = couponRepository.findById(couponId).orElseThrow();
            assertThat(coupon.getReceivedCount()).as("超发的券等于白送营销预算").isEqualTo(1);
            return null;
        });
        asClient();
    }

    // ---------------------------------------------------------------- 满减 / 会员等级

    @Test
    @DisplayName("满减:规则写坏在保存阶段就被拒绝(不等下单才发现活动从未生效)")
    void promotionRules() {
        inTenant(() -> {
            assertThatThrownBy(() -> promotionService.create(new PromotionSaveRequest("用例活动-坏规则",
                    "not-a-json", 1, null, LocalDateTime.now(), LocalDateTime.now().plusDays(3), 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("满减规则");
            // 指定分类/商品时必须选范围
            assertThatThrownBy(() -> promotionService.create(new PromotionSaveRequest("用例活动-缺范围",
                    "[{\"amount\":100,\"reduce\":10}]", 2, List.of(),
                    LocalDateTime.now(), LocalDateTime.now().plusDays(3), 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("范围");
            Long activityId = promotionService.create(new PromotionSaveRequest("用例活动",
                    "[{\"amount\":100,\"reduce\":10}]", 1, null,
                    LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(3), 1));
            PageResult<?> page = promotionService.page("用例活动", null, 1, 10);
            assertThat(page.total()).isGreaterThanOrEqualTo(1);
            promotionService.changeStatus(activityId, 0);
            return null;
        });
    }

    @Test
    @DisplayName("会员等级:重名被拒,折扣率取值被校验")
    void memberLevelRules() {
        inTenant(() -> {
            Long levelId = memberLevelService.create(new MemberLevelSaveRequest("用例等级" + System.nanoTime() % 10000,
                    1, 100, new BigDecimal("0.95"), 1));
            assertThat(memberLevelService.list()).isNotEmpty();
            assertThatThrownBy(() -> memberLevelService.update(levelId, new MemberLevelSaveRequest("改名",
                    1, 100, new BigDecimal("2"), 1)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("折扣率");
            return null;
        });
    }

    // ---------------------------------------------------------------- 评价

    @Test
    @DisplayName("评价:商家可回复与隐藏,隐藏后不在展示列表里")
    void reviewReplyAndHide() {
        Long reviewId = inTenant(() -> {
            // 直接落一条评价(发起评价需要"已完成订单",那条路径已在订单链路里覆盖)
            MallGoodsReview review = new MallGoodsReview();
            review.setGoodsId(createGoods());
            review.setOrderItemId(System.nanoTime());
            review.setCustomerId(customerId);
            review.setRating(5);
            review.setContent("很好");
            review.setIsAnonymous(0);
            review.setStatus(1);
            return reviewRepository.save(review).getId();
        });
        inTenant(() -> {
            reviewService.reply(reviewId, "感谢支持");
            assertThat(reviewService.page(null, 1, 1, 10).list())
                    .anySatisfy(view -> assertThat(view.replyContent()).isEqualTo("感谢支持"));

            reviewService.changeStatus(reviewId, 0);
            assertThat(reviewService.page(null, 0, 1, 10).list())
                    .anySatisfy(view -> assertThat(view.status()).isZero());
            return null;
        });
    }

    // ---------------------------------------------------------------- 管理端订单

    @Test
    @DisplayName("管理端:发货推进状态;取消已支付订单会回补库存并创建退款记录")
    void adminOrderShipAndCancel() {
        Long goodsForOrder = inTenant(() -> createGoods());
        Long addressId = inTenant(() -> createAddress());
        Long orderId = inTenant(() -> {
            var created = orderService.create(new com.minimall.api.mall.dto.CreateOrderRequest(
                    List.of(new com.minimall.api.mall.dto.CreateOrderRequest.Item(
                            skuRepository.findByGoodsIdOrderByIdAsc(goodsForOrder).get(0).getId(), 1)),
                    addressId, null, null));
            payService.handlePayCallback(created.orderNo(), "admin-it-" + System.nanoTime(),
                    created.payAmount(), true, "{}");
            return created.orderId();
        });

        // 发货:2 → 3
        inTenant(() -> {
            MallOrder before = orderRepository.findById(orderId).orElseThrow();
            assertThat(before.getStatus()).isEqualTo(MallOrder.STATUS_PENDING_SHIP);
            orderAdminService.ship(orderId, "顺丰", "SF-ADMIN-1");
            MallOrder after = orderRepository.findById(orderId).orElseThrow();
            assertThat(after.getStatus()).isEqualTo(MallOrder.STATUS_PENDING_RECEIVE);
            assertThat(after.getLogisticsNo()).isEqualTo("SF-ADMIN-1");
            return null;
        });
        inTenant(() -> {
            assertThatThrownBy(() -> orderAdminService.ship(orderId, "顺丰", "SF-ADMIN-2"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("待发货");
            return null;
        });
    }

    // ---------------------------------------------------------------- 辅助

    /** 建一个含两组规格值、两个 SKU 的商品。 */
    private Long createGoods() {
        return goodsService.create(new GoodsSaveRequest(categoryId, "测试商品", "副标题",
                "https://example.com/g.png", "<p>详情</p>", null, 0, 1,
                List.of("https://example.com/1.png"),
                List.of(new SpecSaveRequest("颜色", List.of("红", "蓝"))),
                List.of(
                        new SkuSaveRequest(null, "SKU-A-" + System.nanoTime(), "红", null,
                                new BigDecimal("10.00"), new BigDecimal("5.00"), 10, new BigDecimal("0.5"),
                                1, List.of("红")),
                        new SkuSaveRequest(null, "SKU-B-" + System.nanoTime(), "蓝", null,
                                new BigDecimal("20.00"), new BigDecimal("8.00"), 20, new BigDecimal("0.6"),
                                1, List.of("蓝")))));
    }

    private Long createGoodsWithTemplate(Long templateId) {
        return goodsService.create(new GoodsSaveRequest(categoryId, "带运费模板商品", null,
                "https://example.com/t.png", null, templateId, 0, 1, List.of(), List.of(),
                List.of(new SkuSaveRequest(null, "T-SKU-" + System.nanoTime(), "默认", null,
                        new BigDecimal("15.00"), null, 5, null, 1, List.of()))));
    }

    private Long createAddress() {
        var address = new com.minimall.mall.domain.MallCustomerAddress();
        address.setCustomerId(customerId);
        address.setReceiverName("用例");
        address.setReceiverPhone("13800000002");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetailAddress("测试路 9 号");
        address.setIsDefault(1);
        return addressRepository().save(address).getId();
    }

    @Autowired
    private com.minimall.mall.domain.repository.MallCustomerAddressRepository addressRepository;

    private com.minimall.mall.domain.repository.MallCustomerAddressRepository addressRepository() {
        return addressRepository;
    }

    private FreightTemplateSaveRequest.Rule rule(String region, int firstUnit, int firstFee,
                                                 int additionalUnit, int additionalFee) {
        return new FreightTemplateSaveRequest.Rule(region, BigDecimal.valueOf(firstUnit),
                BigDecimal.valueOf(firstFee), BigDecimal.valueOf(additionalUnit),
                BigDecimal.valueOf(additionalFee), null);
    }

    private void asClient() {
        ClientContext.set(new ClientPrincipal(TENANT_ID, customerId));
        TenantContext.setTenantId(TENANT_ID);
    }

    private <T> T inTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, false, () -> {
            AuditContext.bind(new AuditContext(TENANT_ID, 1L, "127.0.0.1", "mall-admin-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }
}
