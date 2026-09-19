package com.minimall.mall.service.support;

import com.minimall.common.BusinessException;
import com.minimall.mall.domain.MallCoupon;
import com.minimall.mall.domain.MallFreightTemplate;
import com.minimall.mall.domain.MallFreightTemplateRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单金额计算(商城设计文档 3.5、3.7)的纯单元测试。
 *
 * <p>这里不启动 Spring、不连数据库:金额计算的边界(门槛差一分钱、续重向上取整、阶梯顺序写反)
 * 是最容易出错也最容易漏测的地方,把它们隔离出来才能把边界测干净。
 */
class OrderAmountCalculatorTest {

    private final OrderAmountCalculator calculator = new OrderAmountCalculator();

    private static final String RULE_NORMAL = "[{\"amount\":100,\"reduce\":10},{\"amount\":200,\"reduce\":30}]";
    /** 运营把阶梯顺序写反的版本(常见笔误)。 */
    private static final String RULE_REVERSED = "[{\"amount\":200,\"reduce\":30},{\"amount\":100,\"reduce\":10}]";

    // ---------------------------------------------------------------- 满减

    @Test
    @DisplayName("满减阶梯:取满足条件里减免最大的那一档")
    void reductionPicksHighestQualifiedTier() {
        assertThat(calculator.reductionFor(RULE_NORMAL, new BigDecimal("99"))).isEqualByComparingTo("0");
        assertThat(calculator.reductionFor(RULE_NORMAL, new BigDecimal("100"))).isEqualByComparingTo("10");
        assertThat(calculator.reductionFor(RULE_NORMAL, new BigDecimal("199.99"))).isEqualByComparingTo("10");
        assertThat(calculator.reductionFor(RULE_NORMAL, new BigDecimal("200"))).isEqualByComparingTo("30");
        assertThat(calculator.reductionFor(RULE_NORMAL, new BigDecimal("9999"))).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("满减阶梯顺序写反也要取到最大减免(不能按 JSON 顺序取第一个满足的)")
    void reductionIsOrderIndependent() {
        assertThat(calculator.reductionFor(RULE_REVERSED, new BigDecimal("250")))
                .as("阶梯写反了仍应减 30,否则用户买 250 元只减 10 元")
                .isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("满减规则写坏时按不减免处理,而不是把订单打崩")
    void brokenRuleFallsBackToZero() {
        assertThat(calculator.reductionFor("not-a-json", new BigDecimal("500"))).isEqualByComparingTo("0");
        assertThat(calculator.reductionFor("{}", new BigDecimal("500"))).isEqualByComparingTo("0");
        assertThat(calculator.reductionFor(null, new BigDecimal("500"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("多个满减活动只取减免最大的一个(活动之间不叠加)")
    void onlyBestPromotionApplies() {
        var candidates = List.of(
                new OrderAmountCalculator.PromotionCandidate(1L, "满100减10",
                        "[{\"amount\":100,\"reduce\":10}]", new BigDecimal("300")),
                new OrderAmountCalculator.PromotionCandidate(2L, "满200减30",
                        "[{\"amount\":200,\"reduce\":30}]", new BigDecimal("300")),
                new OrderAmountCalculator.PromotionCandidate(3L, "满500减80",
                        "[{\"amount\":500,\"reduce\":80}]", new BigDecimal("300")));

        var hit = calculator.bestFullReduction(candidates).orElseThrow();
        assertThat(hit.activityId()).isEqualTo(2L);
        assertThat(hit.discount()).isEqualByComparingTo("30");
    }

    @Test
    @DisplayName("没有任何活动满足门槛时不减免")
    void noPromotionMatched() {
        var candidates = List.of(new OrderAmountCalculator.PromotionCandidate(1L, "满500减80",
                "[{\"amount\":500,\"reduce\":80}]", new BigDecimal("300")));
        assertThat(calculator.bestFullReduction(candidates)).isEmpty();
    }

    // ---------------------------------------------------------------- 优惠券

    @Test
    @DisplayName("满减券:未达门槛不抵扣,达到门槛按面额抵扣")
    void couponRespectsThreshold() {
        MallCoupon coupon = coupon(MallCoupon.TYPE_FULL_REDUCTION, new BigDecimal("20"), null,
                new BigDecimal("100"));
        assertThat(calculator.couponDiscount(coupon, new BigDecimal("99.99"))).isEqualByComparingTo("0");
        assertThat(calculator.couponDiscount(coupon, new BigDecimal("100"))).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("折扣券:按折扣率抵扣")
    void discountCouponCalculatesByRate() {
        MallCoupon coupon = coupon(MallCoupon.TYPE_DISCOUNT, null, new BigDecimal("0.90"), BigDecimal.ZERO);
        // 100 元打 9 折 = 抵扣 10 元
        assertThat(calculator.couponDiscount(coupon, new BigDecimal("100"))).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("折扣率配成 0 或 >=1 属于配置错误,按不抵扣处理")
    void invalidDiscountRateIsIgnored() {
        assertThat(calculator.couponDiscount(
                coupon(MallCoupon.TYPE_DISCOUNT, null, BigDecimal.ZERO, BigDecimal.ZERO),
                new BigDecimal("100"))).isEqualByComparingTo("0");
        assertThat(calculator.couponDiscount(
                coupon(MallCoupon.TYPE_DISCOUNT, null, BigDecimal.ONE, BigDecimal.ZERO),
                new BigDecimal("100"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("抵扣额不会超过待抵扣金额(券面额大于订单金额时)")
    void couponNeverExceedsAmount() {
        MallCoupon coupon = coupon(MallCoupon.TYPE_CASH, new BigDecimal("50"), null, BigDecimal.ZERO);
        assertThat(calculator.couponDiscount(coupon, new BigDecimal("30"))).isEqualByComparingTo("30");
    }

    // ---------------------------------------------------------------- 运费

    @Test
    @DisplayName("按件数:首件 + 续件按步长向上取整")
    void freightByQuantityRoundsUp() {
        MallFreightTemplateRule rule = rule("ALL", "1", "5", "2", "3", null);
        // 首件 5 元,续件每 2 件 3 元
        assertThat(freightQty(rule, 1)).isEqualByComparingTo("5");
        assertThat(freightQty(rule, 3)).isEqualByComparingTo("8");   // 5 + ceil(2/2)*3
        assertThat(freightQty(rule, 4)).isEqualByComparingTo("11");  // 5 + ceil(3/2)*3 = 5+2*3
        assertThat(freightQty(rule, 5)).isEqualByComparingTo("11");
    }

    @Test
    @DisplayName("满额包邮:达到门槛运费为 0")
    void freeShippingApplies() {
        MallFreightTemplateRule rule = rule("ALL", "1", "5", "2", "3", new BigDecimal("100"));
        var group = new OrderAmountCalculator.FreightGroup(1L, MallFreightTemplate.CHARGE_BY_QUANTITY,
                new BigDecimal("3"), BigDecimal.ZERO, new BigDecimal("150"));
        assertThat(calculator.freight(group, List.of(rule), "广东省")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("运费规则:优先命中收货省份,没有则用 ALL 兜底")
    void regionRuleWinsThenFallsBack() {
        MallFreightTemplateRule all = rule("ALL", "1", "5", "1", "1", null);
        MallFreightTemplateRule guangdong = rule("广东省,广西壮族自治区", "1", "2", "1", "1", null);

        assertThat(calculator.pickRule(List.of(all, guangdong), "广东省")).isSameAs(guangdong);
        assertThat(calculator.pickRule(List.of(all, guangdong), "浙江省")).isSameAs(all);
        assertThat(calculator.pickRule(List.of(guangdong), "浙江省"))
                .as("没有兜底规则时返回 null,由调用方按 0 处理并记日志")
                .isNull();
    }

    @Test
    @DisplayName("没有可用规则时运费按 0 处理(商家少收,也不能让用户下不了单)")
    void missingRuleReturnsZero() {
        var group = new OrderAmountCalculator.FreightGroup(1L, MallFreightTemplate.CHARGE_BY_QUANTITY,
                BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("50"));
        assertThat(calculator.freight(group, List.of(), "广东省")).isEqualByComparingTo("0");
        assertThat(calculator.freight(group, null, "广东省")).isEqualByComparingTo("0");
    }

    // ---------------------------------------------------------------- 实付金额

    @Test
    @DisplayName("实付金额 = 商品总额 - 满减 - 券 + 运费,并规整为两位小数")
    void payableCalculation() {
        BigDecimal payable = calculator.payable(new BigDecimal("100.005"), new BigDecimal("10"),
                new BigDecimal("5"), new BigDecimal("8"));
        assertThat(payable).isEqualByComparingTo("93.01");
    }

    @Test
    @DisplayName("优惠超过商品金额时拒绝(不能让订单变成负数)")
    void payableRejectsOverDiscount() {
        assertThatThrownBy(() -> calculator.payable(new BigDecimal("10"), new BigDecimal("10"),
                new BigDecimal("5"), BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过订单金额");
    }

    // ---------------------------------------------------------------- 辅助

    private BigDecimal freightQty(MallFreightTemplateRule rule, int quantity) {
        var group = new OrderAmountCalculator.FreightGroup(rule.getTemplateId(),
                MallFreightTemplate.CHARGE_BY_QUANTITY, BigDecimal.valueOf(quantity),
                BigDecimal.ZERO, new BigDecimal("10"));
        return calculator.freight(group, List.of(rule), "广东省");
    }

    private MallCoupon coupon(int type, BigDecimal amount, BigDecimal rate, BigDecimal minAmount) {
        MallCoupon coupon = new MallCoupon();
        coupon.setCouponType(type);
        coupon.setDiscountAmount(amount);
        coupon.setDiscountRate(rate);
        coupon.setMinOrderAmount(minAmount);
        return coupon;
    }

    private MallFreightTemplateRule rule(String region, String firstUnit, String firstFee,
                                         String additionalUnit, String additionalFee, BigDecimal freeShipping) {
        MallFreightTemplateRule rule = new MallFreightTemplateRule();
        rule.setTemplateId(1L);
        rule.setRegion(region);
        rule.setFirstUnit(new BigDecimal(firstUnit));
        rule.setFirstFee(new BigDecimal(firstFee));
        rule.setAdditionalUnit(new BigDecimal(additionalUnit));
        rule.setAdditionalFee(new BigDecimal(additionalFee));
        rule.setFreeShippingAmount(freeShipping);
        return rule;
    }
}
