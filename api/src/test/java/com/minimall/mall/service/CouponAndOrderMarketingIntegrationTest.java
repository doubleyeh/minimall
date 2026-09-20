package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.mall.api.dto.ClientCouponView;
import com.minimall.mall.api.dto.CouponSaveRequest;
import com.minimall.mall.api.dto.CouponView;
import com.minimall.mall.api.dto.CreateOrderRequest;
import com.minimall.mall.api.dto.FreightTemplateSaveRequest;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.api.dto.PromotionSaveRequest;
import com.minimall.mall.domain.MallCoupon;
import com.minimall.mall.domain.MallCouponRecord;
import com.minimall.mall.domain.MallFreightTemplate;
import com.minimall.mall.domain.MallFreightTemplateRule;
import com.minimall.mall.domain.MallPromotionFullReduction;
import com.minimall.mall.domain.repository.MallCouponRecordRepository;
import com.minimall.mall.domain.repository.MallCouponRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 优惠券与订单侧的营销计算(商城设计文档 3.5、3.7、3.10)。
 *
 * <p>为什么把这两块放在一起:优惠券的规则散落在三处 —— 领券时的门槛与限领({@code claim})、
 * 我的券列表里的"读时修正过期"、以及**下单时的可用性判定**({@code resolveCoupon})。
 * 三处各自都有一套判断,只测其中一处等于没测:真实故障往往是"领的时候拦住了、下单时没拦"。
 *
 * <p>金额是最不该凭直觉写断言的地方,所以本类的期望值全部按 {@code OrderAmountCalculator}
 * 的公式算出来的确定值,而不是"大于 0"这类模糊断言 —— 后者在公式写错时照样通过:
 * <ul>
 *   <li>满减:取"门槛 ≤ 金额"的最高一档</li>
 *   <li>优惠券门槛用**满减之后**的金额判断</li>
 *   <li>运费:{@code firstFee + ceil((计量 - firstUnit) / additionalUnit) × additionalFee},
 *       区域规则优先于 ALL 兜底,达到包邮门槛则为 0</li>
 * </ul>
 */
class CouponAndOrderMarketingIntegrationTest extends MallClientServiceTestBase {

    private static final String NAME_PREFIX = "it营销-";

    @Autowired
    private CouponService couponService;
    @Autowired
    private FreightTemplateService freightTemplateService;
    @Autowired
    private PromotionService promotionService;
    @Autowired
    private MallCouponRepository couponRepository;
    @Autowired
    private MallCouponRecordRepository couponRecordRepository;

    /**
     * 清理本类造的数据。
     *
     * <p>用 JdbcTemplate 按表名直删而不是走仓储:这三组数据都是"主子表"(券+领取记录、
     * 活动+范围、模板+规则),仓储接口上未必有现成的级联删除方法,而测试清理不值得为它去改仓储。
     * 表名与 V4 迁移一致。
     */
    @AfterEach
    void cleanMarketingData() {
        inTenant(() -> {
            jdbcTemplate.update("DELETE FROM mall_coupon_record WHERE coupon_id IN "
                    + "(SELECT id FROM mall_coupon WHERE coupon_name LIKE ?)", NAME_PREFIX + "%");
            jdbcTemplate.update("DELETE FROM mall_coupon WHERE coupon_name LIKE ?", NAME_PREFIX + "%");
            jdbcTemplate.update("DELETE FROM mall_promotion_full_reduction_scope WHERE activity_id IN "
                    + "(SELECT id FROM mall_promotion_full_reduction WHERE activity_name LIKE ?)", NAME_PREFIX + "%");
            jdbcTemplate.update("DELETE FROM mall_promotion_full_reduction WHERE activity_name LIKE ?", NAME_PREFIX + "%");
            jdbcTemplate.update("DELETE FROM mall_freight_template_rule WHERE template_id IN "
                    + "(SELECT id FROM mall_freight_template WHERE template_name LIKE ?)", NAME_PREFIX + "%");
            jdbcTemplate.update("DELETE FROM mall_freight_template WHERE template_name LIKE ?", NAME_PREFIX + "%");
            return null;
        });
    }

    // ---------------------------------------------------------------- 夹具

    private CouponSaveRequest couponRequest(int type, String amount, String rate, String minOrder,
                                            int totalCount, int perCustomerLimit) {
        return new CouponSaveRequest(NAME_PREFIX + System.nanoTime(), type,
                amount == null ? null : new BigDecimal(amount),
                rate == null ? null : new BigDecimal(rate),
                new BigDecimal(minOrder), totalCount, perCustomerLimit,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null);
    }

    private Long createCoupon(CouponSaveRequest request) {
        return inTenant(() -> couponService.create(request));
    }

    /** 一张"满 100 减 10"的券,总量与限领可控。 */
    private Long createFullReductionCoupon(int totalCount, int perCustomerLimit) {
        return createCoupon(couponRequest(MallCoupon.TYPE_FULL_REDUCTION, "10.00", null, "100", totalCount, perCustomerLimit));
    }

    private Long claimAs(Long ownerCustomerId, Long couponId) {
        return asClient(ownerCustomerId, () -> couponService.claim(couponId));
    }

    /** 带券下单(基类的 createOrder 固定不传券)。 */
    private OrderCreateResponse createOrderWithCoupon(Long ownerCustomerId, int quantity, Long couponRecordId) {
        return asClient(ownerCustomerId, () -> orderService.create(new CreateOrderRequest(
                List.of(new CreateOrderRequest.Item(skuId, quantity)), addressId, couponRecordId, null)));
    }

    private MallCoupon coupon(Long couponId) {
        return inTenant(() -> couponRepository.findById(couponId).orElseThrow());
    }

    private MallCouponRecord record(Long recordId) {
        return inTenant(() -> couponRecordRepository.findById(recordId).orElseThrow());
    }

    private void patchCoupon(String sql, Object... args) {
        inTenant(() -> {
            jdbcTemplate.update(sql, args);
            return null;
        });
    }

    // ================================================================ 优惠券:配置

    @Test
    @DisplayName("新建优惠券:有效期与金额配置都要校验")
    void createValidatesConfiguration() {
        CouponSaveRequest reversed = new CouponSaveRequest(NAME_PREFIX + "时间反了", MallCoupon.TYPE_FULL_REDUCTION,
                new BigDecimal("10.00"), null, new BigDecimal("100"), 10, 1,
                LocalDateTime.now().plusDays(5), LocalDateTime.now().plusDays(1), null);
        assertThatThrownBy(() -> inTenant(() -> couponService.create(reversed)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("有效期结束时间不能早于开始时间");

        // 折扣率必须落在 (0,1):等于 1 是"不打折",大于 1 是加价,都说明配置写错了
        assertThatThrownBy(() -> createCoupon(couponRequest(MallCoupon.TYPE_DISCOUNT, null, null, "0", 10, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("折扣率必须在 0 与 1 之间");
        assertThatThrownBy(() -> createCoupon(couponRequest(MallCoupon.TYPE_DISCOUNT, null, "1", "0", 10, 1)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> createCoupon(couponRequest(MallCoupon.TYPE_DISCOUNT, null, "1.5", "0", 10, 1)))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> createCoupon(couponRequest(MallCoupon.TYPE_FULL_REDUCTION, "0", null, "100", 10, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请填写大于 0 的减免金额");
    }

    @Test
    @DisplayName("新建优惠券:按类型只保留一个字段,避免库里两个值并存导致口径歧义")
    void createKeepsOnlyTheFieldOfItsType() {
        Long discountId = createCoupon(couponRequest(MallCoupon.TYPE_DISCOUNT, "5.00", "0.9", "0", 10, 1));
        MallCoupon discount = coupon(discountId);
        assertThat(discount.getDiscountRate()).isEqualByComparingTo("0.9");
        assertThat(discount.getDiscountAmount()).as("折扣券不该同时留一个减免金额").isNull();
        assertThat(discount.getReceivedCount()).isZero();

        Long fullReductionId = createFullReductionCoupon(10, 1);
        MallCoupon fullReduction = coupon(fullReductionId);
        assertThat(fullReduction.getDiscountAmount()).isEqualByComparingTo("10.00");
        assertThat(fullReduction.getDiscountRate()).isNull();
        assertThat(fullReduction.getStatus()).as("不传状态时默认启用").isEqualTo(1);
    }

    @Test
    @DisplayName("修改优惠券:发放总量不能改到已领取数量以下")
    void updateRejectsTotalBelowReceived() {
        Long couponId = createFullReductionCoupon(10, 2);
        claimAs(customerId, couponId);
        claimAs(customerId, couponId);
        assertThat(coupon(couponId).getReceivedCount()).isEqualTo(2);

        assertThatThrownBy(() -> inTenant(() -> {
            couponService.update(couponId, couponRequest(MallCoupon.TYPE_FULL_REDUCTION, "10.00", null, "100", 1, 2));
            return null;
        }))
                .as("改成 1 会让剩余名额变成负数,后续领取全部失败")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能小于已领取数量");

        inTenant(() -> {
            couponService.update(couponId, couponRequest(MallCoupon.TYPE_FULL_REDUCTION, "12.00", null, "100", 10, 2));
            return null;
        });
        assertThat(coupon(couponId).getDiscountAmount()).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("优惠券启停:状态值必须合法")
    void changeStatusValidates() {
        Long couponId = createFullReductionCoupon(10, 1);

        assertThatThrownBy(() -> inTenant(() -> {
            couponService.changeStatus(couponId, 7);
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能是 0(停用)或 1(启用)");

        inTenant(() -> {
            couponService.changeStatus(couponId, 0);
            return null;
        });
        assertThat(coupon(couponId).getStatus()).isZero();
    }

    // ================================================================ 优惠券:领取

    @Test
    @DisplayName("领取:不在可领取时间内、已领完、超过每人限领,都要拒绝")
    void claimEnforcesWindowLimitAndStock() {
        // 未开始
        CouponSaveRequest future = new CouponSaveRequest(NAME_PREFIX + "未开始", MallCoupon.TYPE_FULL_REDUCTION,
                new BigDecimal("10.00"), null, new BigDecimal("0"), 10, 1,
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(5), null);
        Long futureId = createCoupon(future);
        assertThatThrownBy(() -> claimAs(customerId, futureId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在可领取时间内");

        // 停用的也不能领
        Long disabledId = createFullReductionCoupon(10, 1);
        inTenant(() -> {
            couponService.changeStatus(disabledId, 0);
            return null;
        });
        assertThatThrownBy(() -> claimAs(customerId, disabledId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在可领取时间内");

        // 每人限领
        Long limitedId = createFullReductionCoupon(10, 1);
        claimAs(customerId, limitedId);
        assertThatThrownBy(() -> claimAs(customerId, limitedId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("每人限领 1 张");

        // 领完:总量 1,另一个客户来领
        Long singleId = createFullReductionCoupon(1, 1);
        Long recordId = claimAs(customerId, singleId);
        assertThat(record(recordId).getStatus()).isEqualTo(MallCouponRecord.STATUS_UNUSED);
        assertThat(record(recordId).getReceiveTime()).isNotNull();
        assertThat(coupon(singleId).getReceivedCount()).isEqualTo(1);

        assertThatThrownBy(() -> claimAs(otherCustomerId, singleId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DATA_CONFLICT))
                .hasMessageContaining("已领完");
    }

    @Test
    @DisplayName("领券列表:游客也能看,并如实标注可领取/已领取/已领完")
    void claimableMarksAvailabilityForGuestAndCustomer() {
        Long couponId = createFullReductionCoupon(1, 1);

        // 游客(没有客户身份)也要能看:不登录就不展示领券入口,等于把新客挡在门外
        List<ClientCouponView> guestView = inTenant(() -> couponService.claimable());
        ClientCouponView forGuest = guestView.stream().filter(view -> view.couponId().equals(couponId))
                .findFirst().orElseThrow();
        // 领券列表的可领取文案走 statusText 字段(与"我的券"的状态文案共用一个位置)
        assertThat(forGuest.statusText()).as("游客看到的可领取文案").isEqualTo("可领取");
        assertThat(forGuest.recordId()).as("领券列表不带领取记录").isNull();
        assertThat(forGuest.status()).isNull();

        claimAs(customerId, couponId);
        // 必须带客户身份查:可领取文案里的"已领取"依据的是**这个人**领过几张,
        // 没有客户身份时拿不到这个数(游客视角与"已领完"共用同一条兜底判断)
        ClientCouponView afterClaim = asClient(customerId, () -> couponService.claimable()).stream()
                .filter(view -> view.couponId().equals(couponId)).findFirst().orElseThrow();
        assertThat(afterClaim.statusText()).as("已到每人限领上限").isEqualTo("已领取");

        // 换个客户看:总量已发完
        Long exhaustedId = createFullReductionCoupon(1, 1);
        claimAs(customerId, exhaustedId);
        ClientCouponView forOthers = inTenant(() -> couponService.claimable()).stream()
                .filter(view -> view.couponId().equals(exhaustedId)).findFirst().orElseThrow();
        assertThat(forOthers.statusText()).isEqualTo("已领完");
    }

    @Test
    @DisplayName("我的优惠券:只返回自己的;过期未跑任务时读的时候修正为已过期")
    void mineFiltersAndFixesExpiredStatus() {
        Long couponId = createFullReductionCoupon(10, 2);
        Long recordId = claimAs(customerId, couponId);
        claimAs(otherCustomerId, couponId);

        List<ClientCouponView> mine = asClient(customerId, () -> couponService.mine(null));
        assertThat(mine).extracting(ClientCouponView::recordId).containsExactly(recordId);
        assertThat(mine.get(0).statusText()).as("我的券给的是使用状态文案").isEqualTo("未使用");
        assertThat(mine.get(0).validEndTime()).isNotNull();

        assertThat(asClient(customerId, () -> couponService.mine(MallCouponRecord.STATUS_EXPIRED)))
                .as("未使用状态过滤时不该出现")
                .isEmpty();

        // 把有效期改到过去:定时任务还没跑到,但用户不该看到一张"未使用"的过期券
        patchCoupon("UPDATE mall_coupon SET valid_end_time = ? WHERE id = ?",
                LocalDateTime.now().minusDays(1), couponId);
        List<ClientCouponView> expired = asClient(customerId, () -> couponService.mine(null));
        assertThat(expired.get(0).status()).isEqualTo(MallCouponRecord.STATUS_EXPIRED);
        assertThat(expired.get(0).statusText()).isEqualTo("已过期");
        assertThat(asClient(customerId, () -> couponService.mine(MallCouponRecord.STATUS_EXPIRED))).hasSize(1);
    }

    @Test
    @DisplayName("优惠券列表:按名称与状态过滤,并带上发放量")
    void pageFiltersByNameAndStatus() {
        Long couponId = createFullReductionCoupon(20, 1);
        String name = coupon(couponId).getCouponName();

        PageResult<CouponView> byName = inTenant(() -> couponService.page(name, null, 1, 10));
        assertThat(byName.list()).extracting(CouponView::id).containsExactly(couponId);
        assertThat(byName.list().get(0).totalCount()).isEqualTo(20);
        assertThat(byName.list().get(0).receivedCount()).isZero();

        assertThat(inTenant(() -> couponService.page(name, 0, 1, 10)).total())
                .as("该券是启用状态")
                .isZero();
    }

    // ================================================================ 下单:优惠券

    @Test
    @DisplayName("下单:未达到优惠券门槛时拒绝(门槛按满减之后的金额判断)")
    void orderRejectsCouponBelowThreshold() {
        Long couponId = createCoupon(couponRequest(MallCoupon.TYPE_FULL_REDUCTION, "10.00", null, "500", 10, 1));
        Long recordId = claimAs(customerId, couponId);

        // 夹具 SKU 单价 50,买 2 件 = 100,达不到 500 的门槛
        assertThatThrownBy(() -> createOrderWithCoupon(customerId, 2, recordId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未达到优惠券使用门槛");
    }

    @Test
    @DisplayName("下单:别人的券按不存在处理,已用的券按不可用处理")
    void orderRejectsForeignOrUsedCoupon() {
        Long couponId = createFullReductionCoupon(10, 1);
        Long othersRecordId = claimAs(otherCustomerId, couponId);
        assertThatThrownBy(() -> createOrderWithCoupon(customerId, 4, othersRecordId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));

        Long mineRecordId = claimAs(customerId, couponId);
        createOrderWithCoupon(customerId, 4, mineRecordId);
        assertThatThrownBy(() -> createOrderWithCoupon(customerId, 4, mineRecordId))
                .as("同一张券不能用第二次")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("优惠券不可用");
    }

    @Test
    @DisplayName("下单:停用或过期的券不能抵(定时任务还没跑到也要拦住)")
    void orderRejectsDisabledOrExpiredCoupon() {
        Long disabledId = createFullReductionCoupon(10, 1);
        Long disabledRecord = claimAs(customerId, disabledId);
        inTenant(() -> {
            couponService.changeStatus(disabledId, 0);
            return null;
        });
        assertThatThrownBy(() -> createOrderWithCoupon(customerId, 4, disabledRecord))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("优惠券已过期或已停用");

        Long expiredId = createFullReductionCoupon(10, 1);
        Long expiredRecord = claimAs(customerId, expiredId);
        patchCoupon("UPDATE mall_coupon SET valid_end_time = ? WHERE id = ?",
                LocalDateTime.now().minusHours(1), expiredId);
        assertThatThrownBy(() -> createOrderWithCoupon(customerId, 4, expiredRecord))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("优惠券已过期或已停用");
    }

    @Test
    @DisplayName("下单:券成功抵扣,并把领取记录置为已使用且挂到订单上")
    void orderAppliesCouponAndMarksRecordUsed() {
        Long couponId = createFullReductionCoupon(10, 1);
        Long recordId = claimAs(customerId, couponId);

        // 2 件 × 50 = 100,满 100 减 10
        OrderCreateResponse order = createOrderWithCoupon(customerId, 2, recordId);

        assertThat(order.goodsAmount()).isEqualByComparingTo("100.00");
        assertThat(order.couponDiscountAmount()).isEqualByComparingTo("10.00");
        assertThat(order.payAmount()).as("无满减、无运费时:100 - 10").isEqualByComparingTo("90.00");

        MallCouponRecord used = record(recordId);
        assertThat(used.getStatus()).as("券必须被标记为已用,否则同一张券能反复抵扣").isEqualTo(MallCouponRecord.STATUS_USED);
        assertThat(used.getOrderId()).isEqualTo(order.orderId());
        assertThat(used.getUseTime()).isNotNull();
    }

    // ================================================================ 下单:满减与运费

    private Long createPromotion(String rule, int scopeType, List<Long> scopeIds) {
        return inTenant(() -> promotionService.create(new PromotionSaveRequest(NAME_PREFIX + System.nanoTime(),
                rule, scopeType, scopeIds, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), 1)));
    }

    @Test
    @DisplayName("下单:满减取门槛最高的命中档,而不是 JSON 里写的第一个")
    void orderAppliesBestPromotionTier() {
        // 阶梯故意写成"低门槛在前":按书写顺序取第一个满足的,买 200 只会减 10
        createPromotion("[{\"amount\":100,\"reduce\":10},{\"amount\":200,\"reduce\":30}]",
                MallPromotionFullReduction.SCOPE_ALL, null);

        OrderCreateResponse twoUnits = createOrder(customerId, addressId, 2);
        assertThat(twoUnits.goodsAmount()).isEqualByComparingTo("100.00");
        assertThat(twoUnits.promotionDiscountAmount()).isEqualByComparingTo("10.00");
        assertThat(twoUnits.payAmount()).isEqualByComparingTo("90.00");

        OrderCreateResponse fourUnits = createOrder(customerId, addressId, 4);
        assertThat(fourUnits.goodsAmount()).isEqualByComparingTo("200.00");
        assertThat(fourUnits.promotionDiscountAmount()).as("200 元要命中满 200 减 30").isEqualByComparingTo("30.00");
        assertThat(fourUnits.payAmount()).isEqualByComparingTo("170.00");
    }

    @Test
    @DisplayName("下单:按商品范围的满减,范围不含该商品时不抵扣;按分类范围同理")
    void orderAppliesScopedPromotionOnlyWhenMatched() {
        createPromotion("[{\"amount\":100,\"reduce\":15}]",
                MallPromotionFullReduction.SCOPE_GOODS, List.of(goodsId));
        assertThat(createOrder(customerId, addressId, 2).promotionDiscountAmount())
                .as("范围包含该商品")
                .isEqualByComparingTo("15.00");

        // 换一个只覆盖别的商品的活动:上一单已经把范围活动用完?不 —— 活动是独立的,这里再建一个
        inTenant(() -> {
            jdbcTemplate.update("DELETE FROM mall_promotion_full_reduction WHERE activity_name LIKE ?", NAME_PREFIX + "%");
            return null;
        });
        createPromotion("[{\"amount\":100,\"reduce\":15}]",
                MallPromotionFullReduction.SCOPE_GOODS, List.of(999999L));
        assertThat(createOrder(customerId, addressId, 2).promotionDiscountAmount())
                .as("范围不含该商品,覆盖金额为 0,不该计入候选")
                .isEqualByComparingTo("0");

        inTenant(() -> {
            jdbcTemplate.update("DELETE FROM mall_promotion_full_reduction_scope WHERE activity_id NOT IN "
                    + "(SELECT id FROM mall_promotion_full_reduction)");
            jdbcTemplate.update("DELETE FROM mall_promotion_full_reduction WHERE activity_name LIKE ?", NAME_PREFIX + "%");
            return null;
        });
        createPromotion("[{\"amount\":100,\"reduce\":20}]",
                MallPromotionFullReduction.SCOPE_CATEGORY, List.of(0L));
        assertThat(createOrder(customerId, addressId, 2).promotionDiscountAmount())
                .as("夹具商品挂在分类 0 下")
                .isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("下单:范围活动没有任何范围行时跳过它(而不是把全店金额都算进去)")
    void orderSkipsPromotionWithoutScopes() {
        createPromotion("[{\"amount\":50,\"reduce\":10}]",
                MallPromotionFullReduction.SCOPE_GOODS, List.of(goodsId));
        // 删掉范围行:活动还在、范围没了。若不跳过,就会退化成"全场满 50 减 10"
        inTenant(() -> {
            jdbcTemplate.update("DELETE FROM mall_promotion_full_reduction_scope WHERE activity_id IN "
                    + "(SELECT id FROM mall_promotion_full_reduction WHERE activity_name LIKE ?)", NAME_PREFIX + "%");
            return null;
        });

        assertThat(createOrder(customerId, addressId, 2).promotionDiscountAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("下单:运费按收货省份选规则,区域规则优先于 ALL 兜底")
    void orderFreightPrefersRegionRuleOverFallback() {
        Long templateId = inTenant(() -> freightTemplateService.create(new FreightTemplateSaveRequest(
                NAME_PREFIX + "模板", MallFreightTemplate.CHARGE_BY_QUANTITY,
                List.of(new FreightTemplateSaveRequest.Rule(MallFreightTemplateRule.REGION_ALL,
                                new BigDecimal("1"), new BigDecimal("15.00"), new BigDecimal("1"), new BigDecimal("5.00"), null),
                        new FreightTemplateSaveRequest.Rule("广东省",
                                new BigDecimal("1"), new BigDecimal("8.00"), new BigDecimal("1"), new BigDecimal("2.00"), null)))));
        // 夹具商品本来不挂模板,这里直接改库;用例结束后由基类的夹具清理连带删掉
        patchCoupon("UPDATE mall_goods SET freight_template_id = ? WHERE id = ?", templateId, goodsId);

        OrderCreateResponse order = createOrder(customerId, addressId, 3);

        // 收货地址在广东省 → 命中专属规则:8 + ceil((3-1)/1) × 2 = 12
        assertThat(order.freightAmount()).isEqualByComparingTo("12.00");
        assertThat(order.payAmount()).as("50×3 + 12").isEqualByComparingTo("162.00");

        // 另一单改到没有专属规则的省份 → 走 ALL 兜底:15 + 2×5 = 25
        inTenant(() -> {
            jdbcTemplate.update("UPDATE mall_customer_address SET province = ? WHERE id = ?", "黑龙江省", addressId);
            return null;
        });
        assertThat(createOrder(customerId, addressId, 3).freightAmount()).isEqualByComparingTo("25.00");
    }
}
