package com.minimall.mall.service.support;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.domain.MallCoupon;
import com.minimall.mall.domain.MallFreightTemplate;
import com.minimall.mall.domain.MallFreightTemplateRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 订单金额计算(商城设计文档 3.5、3.7)。
 *
 * <p><b>抽成独立类的目的只有一个:可测</b>。优惠与运费的计算是最容易写错、且错了会真金白银少收钱的地方,
 * 把它们从"要连数据库的 service"里剥出来,就能用纯单元测试覆盖各种边界(门槛差一分钱、续重向上取整、
 * 区域没配规则……),不必依赖 MySQL 与 Redis。service 只负责把数据准备成这里需要的样子。
 *
 * <p>所有金额一律 {@link BigDecimal},并且**每次运算都显式指定舍入方式**:
 * 用 double 算钱必然出 0.1+0.2 之类的误差;不指定舍入会在不同 JDK 上得到不同结果。
 */
@Component
public class OrderAmountCalculator {

    private static final Logger log = LoggerFactory.getLogger(OrderAmountCalculator.class);

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    /** 只用于解析满减阶梯规则(库里的 JSON 字符串),不参与 HTTP 序列化,所以独立 new 一个。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 满减活动候选(由服务层准备好"该活动覆盖到的商品金额")。
     *
     * @param coveredAmount 该活动作用范围内的商品金额(scope 过滤后的结果)
     */
    public record PromotionCandidate(Long activityId, String activityName, String reductionRule,
                                     BigDecimal coveredAmount) {
    }

    /** 命中的满减。 */
    public record PromotionHit(Long activityId, String activityName, BigDecimal discount, BigDecimal coveredAmount) {
    }

    /**
     * 满减:同一订单只取**满足条件里减免最大的一个活动**(3.5,活动之间不叠加)。
     *
     * <p>注意坑:阶梯必须按门槛从高到低匹配。如果按 JSON 里的书写顺序取第一个满足的,
     * 运营把阶梯写成"满 200 减 30、满 100 减 10"时(顺序写反是常见笔误),
     * 用户买 300 元会只减 10 元 —— 少减的钱用户会立刻发现并投诉。
     */
    public Optional<PromotionHit> bestFullReduction(List<PromotionCandidate> candidates) {
        PromotionHit best = null;
        for (PromotionCandidate candidate : candidates) {
            BigDecimal discount = reductionFor(candidate.reductionRule(), candidate.coveredAmount());
            if (discount.signum() <= 0) {
                continue;
            }
            if (best == null || discount.compareTo(best.discount()) > 0) {
                best = new PromotionHit(candidate.activityId(), candidate.activityName(), discount,
                        candidate.coveredAmount());
            }
        }
        return Optional.ofNullable(best);
    }

    /** 单个活动的阶梯匹配:取"门槛 <= 金额"里减免最大的那一档。 */
    public BigDecimal reductionFor(String reductionRule, BigDecimal amount) {
        if (reductionRule == null || reductionRule.isBlank() || amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        try {
            JsonNode root = objectMapper.readTree(reductionRule);
            if (!root.isArray()) {
                log.warn("满减规则不是 JSON 数组,已忽略: {}", reductionRule);
                return BigDecimal.ZERO;
            }
            List<BigDecimal[]> tiers = new ArrayList<>();
            for (JsonNode node : root) {
                JsonNode amountNode = node.get("amount");
                JsonNode reduceNode = node.get("reduce");
                if (amountNode == null || reduceNode == null) {
                    continue;
                }
                tiers.add(new BigDecimal[]{amountNode.decimalValue(), reduceNode.decimalValue()});
            }
            return tiers.stream()
                    .filter(tier -> amount.compareTo(tier[0]) >= 0)
                    // 按门槛降序取第一个命中的,等价于"取减免最大的那一档"
                    .max(Comparator.comparing(tier -> tier[0]))
                    .map(tier -> money(tier[1]))
                    .orElse(BigDecimal.ZERO);
        } catch (Exception ex) {
            // 规则写坏时按"不减免"处理:宁可少减也不要把钱算错,同时把原文记进日志便于修正
            log.error("满减规则解析失败,已按不减免处理: {}", reductionRule, ex);
            return BigDecimal.ZERO;
        }
    }

    /**
     * 优惠券抵扣(3.5 第 ② 步)。
     *
     * <p>门槛判断用的是**满减之后的商品金额**(文档明确:满减后金额判断是否达到优惠券门槛),
     * 而不是商品原价 —— 用原价判断会让"凑单到门槛"变成"先满减再凑单",与实际付款对不上。
     *
     * <p>抵扣额不会超过待付金额:优惠券不减运费,也不应该把订单减成负数。
     */
    public BigDecimal couponDiscount(MallCoupon coupon, BigDecimal amountAfterPromotion) {
        if (coupon == null || amountAfterPromotion == null || amountAfterPromotion.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal minAmount = coupon.getMinOrderAmount() == null ? BigDecimal.ZERO : coupon.getMinOrderAmount();
        if (amountAfterPromotion.compareTo(minAmount) < 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount;
        if (coupon.getCouponType() != null && coupon.getCouponType() == MallCoupon.TYPE_DISCOUNT) {
            BigDecimal rate = coupon.getDiscountRate();
            if (rate == null) {
                return BigDecimal.ZERO;
            }
            // 折扣券:抵扣 = 金额 * (1 - 折扣率)。rate 配成 0 或负数属于配置错误,直接不抵扣
            if (rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
                log.warn("折扣券配置异常,已按不抵扣处理: couponId={} rate={}", coupon.getId(), rate);
                return BigDecimal.ZERO;
            }
            discount = amountAfterPromotion.multiply(BigDecimal.ONE.subtract(rate));
        } else {
            discount = coupon.getDiscountAmount() == null ? BigDecimal.ZERO : coupon.getDiscountAmount();
        }
        return money(discount.min(amountAfterPromotion));
    }

    /** 运费计算分组:同一运费模板下的商品算作一组(3.7)。 */
    public record FreightGroup(Long templateId, Integer chargeType, BigDecimal quantity, BigDecimal weight,
                               BigDecimal amount) {
    }

    /**
     * 一组商品的运费(3.7)。
     *
     * <p>规则选择顺序:先找 {@code region} 命中收货省份的规则,没有则用 {@code ALL} 兜底。
     * 都找不到时返回 0 并记 warn —— 返回 0 是"商家少收运费",而抛异常是"用户下不了单",
     * 两者的严重程度差得远;真正该做的是在模板保存时强制要求存在 ALL 规则(见 FreightTemplateService)。
     */
    public BigDecimal freight(FreightGroup group, List<MallFreightTemplateRule> rules, String province) {
        if (group == null || rules == null || rules.isEmpty()) {
            return BigDecimal.ZERO;
        }
        MallFreightTemplateRule rule = pickRule(rules, province);
        if (rule == null) {
            log.warn("运费模板没有可用规则(既无区域规则也无 ALL 兜底),运费按 0 处理: templateId={}",
                    group.templateId());
            return BigDecimal.ZERO;
        }
        BigDecimal freeShipping = rule.getFreeShippingAmount();
        if (freeShipping != null && group.amount() != null && group.amount().compareTo(freeShipping) >= 0) {
            return BigDecimal.ZERO;
        }
        // 计费口径在**模板**上,不在规则上(同一个模板的所有区域必须用同一种口径,
        // 否则"首件"和"首重"混在一起无法计算,见 MallFreightTemplate)
        BigDecimal measure = group.chargeType() != null
                && group.chargeType() == MallFreightTemplate.CHARGE_BY_WEIGHT
                ? (group.weight() == null ? BigDecimal.ZERO : group.weight())
                : (group.quantity() == null ? BigDecimal.ZERO : group.quantity());
        BigDecimal firstUnit = rule.getFirstUnit() == null ? BigDecimal.ZERO : rule.getFirstUnit();
        BigDecimal additionalUnit = rule.getAdditionalUnit();
        BigDecimal fee = rule.getFirstFee() == null ? BigDecimal.ZERO : rule.getFirstFee();
        if (additionalUnit != null && additionalUnit.signum() > 0 && measure.compareTo(firstUnit) > 0) {
            BigDecimal extra = measure.subtract(firstUnit);
            // 向上取整:不足一个续件单位也按一个算。向下取整会造出"多买一件反而更便宜"的定价漏洞
            BigDecimal units = extra.divide(additionalUnit, 0, RoundingMode.CEILING);
            BigDecimal additionalFee = rule.getAdditionalFee() == null ? BigDecimal.ZERO : rule.getAdditionalFee();
            fee = fee.add(units.multiply(additionalFee));
        }
        return money(fee);
    }

    /**
     * 按省份挑规则:{@code region} 是逗号分隔的省份列表,{@code ALL} 为兜底。
     */
    public MallFreightTemplateRule pickRule(List<MallFreightTemplateRule> rules, String province) {
        MallFreightTemplateRule fallback = null;
        for (MallFreightTemplateRule rule : rules) {
            String region = rule.getRegion();
            if (region == null || region.isBlank() || MallFreightTemplateRule.REGION_ALL.equals(region)) {
                if (fallback == null) {
                    fallback = rule;
                }
                continue;
            }
            if (province != null && List.of(region.split(",")).stream()
                    .map(String::trim).anyMatch(province::equals)) {
                return rule;
            }
        }
        return fallback;
    }

    /** 统一的金额规整:两位小数、四舍五入。任何参与落库的金额都要过这个方法。 */
    public BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    /** 校验"实付金额"不会算成负数(优惠叠加超过商品总额时必须挡住,不能让订单变成倒找钱)。 */
    public BigDecimal payable(BigDecimal goodsAmount, BigDecimal promotionDiscount, BigDecimal couponDiscount,
                              BigDecimal freight) {
        BigDecimal payable = goodsAmount
                .subtract(promotionDiscount == null ? BigDecimal.ZERO : promotionDiscount)
                .subtract(couponDiscount == null ? BigDecimal.ZERO : couponDiscount)
                .add(freight == null ? BigDecimal.ZERO : freight);
        if (payable.signum() < 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "优惠金额超过订单金额,请调整优惠券");
        }
        return money(payable);
    }
}
