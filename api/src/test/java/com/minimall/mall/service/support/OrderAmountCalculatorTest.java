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

    // ---------------------------------------------------------------- 防御分支

    /**
     * 这一组覆盖的是"配置缺了一半"的情况。
     *
     * <p>它们的共同点是:**不抛异常比算对更重要**。运营在后台把满减规则写成 JSON 对象、
     * 券只填了折扣率没填面额,这类数据一定会出现;金额计算里抛 NPE 的表现是整个下单页面 500,
     * 而按"不减免"处理最坏只是少减一点钱。所以这里逐条钉住这些分支都返回确定值。
     */
    @Test
    @DisplayName("满减:规则为空/非数组/元素缺字段,一律按不减免处理")
    void reductionIgnoresUnusableRules() {
        assertThat(calculator.reductionFor(null, new BigDecimal("500"))).isEqualByComparingTo("0");
        assertThat(calculator.reductionFor("   ", new BigDecimal("500"))).isEqualByComparingTo("0");
        assertThat(calculator.reductionFor(RULE_NORMAL, null)).isEqualByComparingTo("0");
        assertThat(calculator.reductionFor(RULE_NORMAL, BigDecimal.ZERO)).isEqualByComparingTo("0");
        // 合法 JSON 但不是数组(写成对象是最常见的笔误)
        assertThat(calculator.reductionFor("{\"amount\":100,\"reduce\":10}", new BigDecimal("500")))
                .isEqualByComparingTo("0");
        // 数组里缺 amount 或 reduce 的档位要跳过,而不是当成 0 门槛命中
        assertThat(calculator.reductionFor("[{\"reduce\":10},{\"amount\":100,\"reduce\":20}]",
                new BigDecimal("500"))).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("优惠券:券为空、金额非正、折扣率缺失、面额缺失,都不抵扣")
    void couponDiscountHandlesMissingConfiguration() {
        assertThat(calculator.couponDiscount(null, new BigDecimal("100"))).isEqualByComparingTo("0");

        MallCoupon cash = coupon(MallCoupon.TYPE_CASH, new BigDecimal("20"), null, BigDecimal.ZERO);
        assertThat(calculator.couponDiscount(cash, null)).isEqualByComparingTo("0");
        assertThat(calculator.couponDiscount(cash, BigDecimal.ZERO)).isEqualByComparingTo("0");

        // 折扣券没配折扣率:算不出来就按不抵扣,不能 NPE
        assertThat(calculator.couponDiscount(
                coupon(MallCoupon.TYPE_DISCOUNT, null, null, BigDecimal.ZERO), new BigDecimal("100")))
                .isEqualByComparingTo("0");
        // 满减券没配面额
        assertThat(calculator.couponDiscount(
                coupon(MallCoupon.TYPE_FULL_REDUCTION, null, null, BigDecimal.ZERO), new BigDecimal("100")))
                .isEqualByComparingTo("0");
        // 门槛没配等价于 0 门槛,且抵扣额仍然不超过待付金额
        assertThat(calculator.couponDiscount(
                coupon(MallCoupon.TYPE_CASH, new BigDecimal("20"), null, null), new BigDecimal("1")))
                .isEqualByComparingTo("1");
        // couponType 缺失走"按面额"分支
        MallCoupon noType = new MallCoupon();
        noType.setDiscountAmount(new BigDecimal("15"));
        assertThat(calculator.couponDiscount(noType, new BigDecimal("100"))).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("运费:分组/规则缺失、计量缺失、续件参数缺失,都要算出确定值而不是抛异常")
    void freightHandlesMissingInputs() {
        var threeItems = new OrderAmountCalculator.FreightGroup(1L, MallFreightTemplate.CHARGE_BY_QUANTITY,
                BigDecimal.valueOf(3), BigDecimal.ZERO, new BigDecimal("10"));
        MallFreightTemplateRule stepOne = rule("ALL", "1", "5", "1", "2", null);

        assertThat(calculator.freight(null, List.of(stepOne), "广东省")).isEqualByComparingTo("0");
        assertThat(calculator.freight(threeItems, null, "广东省")).isEqualByComparingTo("0");
        assertThat(calculator.freight(threeItems, List.of(), "广东省")).isEqualByComparingTo("0");

        // 按件计费但件数为 null → 计量按 0,只收首件费
        var noQuantity = new OrderAmountCalculator.FreightGroup(1L, MallFreightTemplate.CHARGE_BY_QUANTITY,
                null, BigDecimal.ZERO, new BigDecimal("10"));
        assertThat(calculator.freight(noQuantity, List.of(stepOne), "广东省")).isEqualByComparingTo("5");

        // 按重量计费但重量为 null → 计量按 0
        var noWeight = new OrderAmountCalculator.FreightGroup(1L, MallFreightTemplate.CHARGE_BY_WEIGHT,
                BigDecimal.valueOf(3), null, new BigDecimal("10"));
        assertThat(calculator.freight(noWeight, List.of(stepOne), "广东省")).isEqualByComparingTo("5");

        // chargeType 缺失按件计费处理:3 件 = 首件 5 + 续 2 件 × 2
        var noChargeType = new OrderAmountCalculator.FreightGroup(1L, null,
                BigDecimal.valueOf(3), BigDecimal.ZERO, new BigDecimal("10"));
        assertThat(calculator.freight(noChargeType, List.of(stepOne), "广东省")).isEqualByComparingTo("9");

        // 续件步长为 0:不收续件费(除零保护)
        assertThat(calculator.freight(threeItems, List.of(rule("ALL", "1", "5", "0", "2", null)), "广东省"))
                .isEqualByComparingTo("5");

        // 件数没超过首件:不收续件费
        var oneItem = new OrderAmountCalculator.FreightGroup(1L, MallFreightTemplate.CHARGE_BY_QUANTITY,
                BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("10"));
        assertThat(calculator.freight(oneItem, List.of(stepOne), "广东省")).isEqualByComparingTo("5");

        // 首件费与续件费都缺失:按 0 计
        MallFreightTemplateRule noFee = new MallFreightTemplateRule();
        noFee.setTemplateId(1L);
        noFee.setRegion("ALL");
        noFee.setFirstUnit(BigDecimal.ONE);
        noFee.setAdditionalUnit(BigDecimal.ONE);
        assertThat(calculator.freight(threeItems, List.of(noFee), "广东省")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("运费规则:区域为空或空白都当兜底,省份为 null 时只走兜底,多条兜底取第一条")
    void pickRuleFallbackRules() {
        MallFreightTemplateRule blankRegion = rule("  ", "1", "5", "1", "1", null);
        MallFreightTemplateRule nullRegion = rule(null, "1", "7", "1", "1", null);
        MallFreightTemplateRule all = rule("ALL", "1", "9", "1", "1", null);

        assertThat(calculator.pickRule(List.of(nullRegion, all), "广东省")).isSameAs(nullRegion);
        assertThat(calculator.pickRule(List.of(blankRegion), "广东省")).isSameAs(blankRegion);
        // 省份为 null:任何区域规则都不可能命中,只能落到兜底
        assertThat(calculator.pickRule(List.of(rule("广东省", "1", "5", "1", "1", null), all), null))
                .isSameAs(all);
    }

    @Test
    @DisplayName("金额规整与实付:null 当作 0 处理")
    void moneyAndPayableTreatNullAsZero() {
        assertThat(calculator.money(null)).isEqualByComparingTo("0");
        assertThat(calculator.payable(new BigDecimal("100"), null, null, null)).isEqualByComparingTo("100");
        assertThat(calculator.payable(new BigDecimal("100"), new BigDecimal("10"), null, new BigDecimal("5")))
                .isEqualByComparingTo("95");
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
