package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.api.dto.FreightTemplateSaveRequest;
import com.minimall.mall.api.dto.FreightTemplateView;
import com.minimall.mall.domain.MallFreightTemplate;
import com.minimall.mall.domain.MallFreightTemplateRule;
import com.minimall.mall.domain.repository.MallFreightTemplateRepository;
import com.minimall.mall.domain.repository.MallFreightTemplateRuleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 运费模板维护(商城设计文档 3.7)。
 *
 * <p>这个类此前**一个测试都没有** —— 它的规则读取路径被订单用例覆盖了({@code OrderAmountCalculator}
 * 的运费计算),但"商家怎么配模板"这条写入路径完全没有保护。写入路径上的三条校验都不是形式主义:
 *
 * <ol>
 *   <li><b>必须有 ALL 兜底</b>:没有它,未配置省份算不出运费,计算器只能返回 0 —— 商家少收运费,
 *       而且要好几天后对账才发现</li>
 *   <li><b>区域不能重复</b>:两条同区域规则时"取第一条命中的"取决于查询顺序,结果不可预期</li>
 *   <li><b>续件步长必须大于 0</b>:步长为 0 会让"续件数量"除零</li>
 * </ol>
 *
 * <p>还有一条容易漏的边界:{@code region} 的归一化。商家把兜底规则写成 {@code NULL}、空串、
 * {@code ALL} 或 {@code all},库里必须都存成同一个值 —— 否则计算器按字符串找兜底时会找不到,
 * 表现成"配了兜底规则却仍然算不出运费"。
 */
class FreightTemplateServiceIntegrationTest extends MallClientServiceTestBase {

    private static final String NAME_PREFIX = "it运费-";

    @Autowired
    private FreightTemplateService freightTemplateService;
    @Autowired
    private MallFreightTemplateRepository templateRepository;
    @Autowired
    private MallFreightTemplateRuleRepository ruleRepository;

    private final List<Long> createdTemplateIds = new ArrayList<>();

    @AfterEach
    void cleanTemplates() {
        inTenant(() -> {
            for (Long templateId : new ArrayList<>(createdTemplateIds)) {
                // 夹具商品可能被用例挂上了模板,而"仍有商品使用"会被删除拦住 —— 先解绑再删。
                // 只解绑指向本用例模板的商品,不去动别的用例造的数据
                jdbcTemplate.update("UPDATE mall_goods SET freight_template_id = NULL WHERE freight_template_id = ?",
                        templateId);
                try {
                    freightTemplateService.delete(templateId);
                } catch (Exception ignored) {
                    // 用例自己删过了
                }
            }
            createdTemplateIds.clear();
            return null;
        });
    }

    // ---------------------------------------------------------------- 夹具

    private static FreightTemplateSaveRequest.Rule rule(String region, String firstFee, String additionalUnit,
                                                       String additionalFee) {
        return new FreightTemplateSaveRequest.Rule(region, new BigDecimal("1"),
                new BigDecimal(firstFee), additionalUnit == null ? null : new BigDecimal(additionalUnit),
                new BigDecimal(additionalFee), null);
    }

    /** 一条合法的兜底规则。 */
    private static FreightTemplateSaveRequest.Rule fallback() {
        return rule(MallFreightTemplateRule.REGION_ALL, "10.00", "1", "2.00");
    }

    private Long createTemplate(FreightTemplateSaveRequest request) {
        Long id = inTenant(() -> freightTemplateService.create(request));
        createdTemplateIds.add(id);
        return id;
    }

    private Long createTemplate(String name, List<FreightTemplateSaveRequest.Rule> rules) {
        return createTemplate(new FreightTemplateSaveRequest(name, MallFreightTemplate.CHARGE_BY_QUANTITY, rules));
    }

    private String nextName() {
        return NAME_PREFIX + System.nanoTime();
    }

    // ================================================================ 校验

    @Test
    @DisplayName("新建模板:缺 ALL 兜底、区域重复、续件步长为 0 都要拒绝")
    void createValidatesRules() {
        assertThatThrownBy(() -> inTenant(() -> freightTemplateService.create(new FreightTemplateSaveRequest(
                nextName(), MallFreightTemplate.CHARGE_BY_QUANTITY, List.of(rule("广东省", "8.00", "1", "2.00"))))))
                .as("没有兜底规则时,未配置省份算不出运费,计算器只能返回 0")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须配置一条不限区域(ALL)的兜底规则");

        // 两条规则都归一化成 ALL:等于同一区域配了两遍,"取第一条命中的"会依赖查询顺序
        assertThatThrownBy(() -> inTenant(() -> freightTemplateService.create(new FreightTemplateSaveRequest(
                nextName(), MallFreightTemplate.CHARGE_BY_QUANTITY,
                List.of(fallback(), rule("all", "12.00", "1", "3.00"))))))
                .as("小写的 all 也要归一化成 ALL,否则重复检查会漏掉它")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存在重复的适用区域");

        assertThatThrownBy(() -> inTenant(() -> freightTemplateService.create(new FreightTemplateSaveRequest(
                nextName(), MallFreightTemplate.CHARGE_BY_QUANTITY,
                List.of(rule(MallFreightTemplateRule.REGION_ALL, "10.00", "0", "2.00"))))))
                .as("步长为 0 会让续件数量除零")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("续件/续重步长必须大于 0");
    }

    @Test
    @DisplayName("新建模板:名称不能重复,区域的空白与小写都被归一化")
    void createNormalizesRegionAndRejectsDuplicateName() {
        String name = nextName();
        // 兜底规则写成"带空格的 all":库里必须存成 ALL,否则计算器按字符串找不到它
        Long templateId = createTemplate(name, List.of(rule("  all  ", "10.00", "1", "2.00")));
        inTenant(() -> {
            List<MallFreightTemplateRule> rules = ruleRepository.findByTemplateIdOrderByIdAsc(templateId);
            assertThat(rules).hasSize(1);
            assertThat(rules.get(0).getRegion()).isEqualTo(MallFreightTemplateRule.REGION_ALL);
            return null;
        });

        assertThatThrownBy(() -> inTenant(() -> freightTemplateService.create(new FreightTemplateSaveRequest(
                name, MallFreightTemplate.CHARGE_BY_QUANTITY, List.of(fallback())))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板名称已存在");

        // region 为 null 也算兜底,同样要归一化成 ALL
        Long nullRegionId = createTemplate(nextName(), List.of(rule(null, "6.00", "1", "1.00")));
        inTenant(() -> {
            assertThat(ruleRepository.findByTemplateIdOrderByIdAsc(nullRegionId).get(0).getRegion())
                    .isEqualTo(MallFreightTemplateRule.REGION_ALL);
            return null;
        });
    }

    // ================================================================ 修改与删除

    @Test
    @DisplayName("修改模板:规则是全量替换,改成已被占用的名称要拒绝")
    void updateReplacesRulesWholesale() {
        String name = nextName();
        Long templateId = createTemplate(name, List.of(
                fallback(), rule("广东省", "8.00", "1", "2.00"), rule("广西壮族自治区", "9.00", "1", "2.00")));

        // 用自己原来的名字提交:不该被"名称已存在"挡住(判断要排除自身)
        inTenant(() -> {
            freightTemplateService.update(templateId, new FreightTemplateSaveRequest(name,
                    MallFreightTemplate.CHARGE_BY_WEIGHT, List.of(rule("福建省", "7.00", "2", "3.00"), fallback())));
            return null;
        });
        inTenant(() -> {
            MallFreightTemplate template = templateRepository.findById(templateId).orElseThrow();
            assertThat(template.getChargeType()).as("计费口径也跟着改").isEqualTo(MallFreightTemplate.CHARGE_BY_WEIGHT);
            // 全量替换:原来那三条(含广东与广西)都不该留下,否则商家以为删掉的区域还在收运费
            assertThat(ruleRepository.findByTemplateIdOrderByIdAsc(templateId))
                    .extracting(MallFreightTemplateRule::getRegion)
                    .containsExactlyInAnyOrder("福建省", MallFreightTemplateRule.REGION_ALL);

            // 换成已被别的模板占用的名字 → 拒绝(与上面"用自己名字提交"形成对照:
            // 差别的全部来源就是那条排除自身的条件)
            return null;
        });

        String otherName = nextName();
        Long otherTemplateId = inTenant(() -> freightTemplateService.create(new FreightTemplateSaveRequest(
                otherName, MallFreightTemplate.CHARGE_BY_QUANTITY, List.of(fallback()))));
        createdTemplateIds.add(otherTemplateId);

        assertThatThrownBy(() -> inTenant(() -> {
            freightTemplateService.update(templateId, new FreightTemplateSaveRequest(
                    otherName, MallFreightTemplate.CHARGE_BY_QUANTITY, List.of(fallback())));
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板名称已存在");
    }

    @Test
    @DisplayName("删除模板:仍有商品在用就必须拒绝(静默解绑会让商品突然变包邮)")
    void deleteRejectsTemplateInUse() {
        Long templateId = createTemplate(nextName(), List.of(fallback()));
        // 夹具商品本来不挂模板;直接改库把它挂上去
        inTenant(() -> {
            jdbcTemplate.update("UPDATE mall_goods SET freight_template_id = ? WHERE id = ?", templateId, goodsId);
            return null;
        });

        assertThatThrownBy(() -> inTenant(() -> {
            freightTemplateService.delete(templateId);
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DATA_CONFLICT))
                .hasMessageContaining("仍有商品使用该运费模板");

        // 解绑后可以删,且规则要一并清掉(留着就是孤儿行)
        inTenant(() -> {
            jdbcTemplate.update("UPDATE mall_goods SET freight_template_id = NULL WHERE id = ?", goodsId);
            freightTemplateService.delete(templateId);
            return null;
        });
        createdTemplateIds.remove(templateId);
        inTenant(() -> {
            assertThat(templateRepository.findById(templateId)).isEmpty();
            assertThat(ruleRepository.findByTemplateIdOrderByIdAsc(templateId)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("模板不存在:详情、修改、删除都按不存在处理")
    void unknownTemplateIsNotFound() {
        assertThatThrownBy(() -> inTenant(() -> freightTemplateService.detail(999999L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("运费模板不存在");
        assertThatThrownBy(() -> inTenant(() -> {
            freightTemplateService.update(999999L, new FreightTemplateSaveRequest(
                    nextName(), MallFreightTemplate.CHARGE_BY_QUANTITY, List.of(fallback())));
            return null;
        }))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> inTenant(() -> {
            freightTemplateService.delete(999999L);
            return null;
        }))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("列表与详情:都要带上规则明细(管理端表单要用它回填)")
    void listAndDetailCarryRules() {
        String name = nextName();
        Long templateId = createTemplate(name, List.of(fallback(), rule("广东省", "8.00", "2", "3.00")));

        FreightTemplateView detail = inTenant(() -> freightTemplateService.detail(templateId));
        assertThat(detail.templateName()).isEqualTo(name);
        assertThat(detail.rules()).extracting(FreightTemplateView.Rule::region)
                .containsExactlyInAnyOrder(MallFreightTemplateRule.REGION_ALL, "广东省");

        List<FreightTemplateView> all = inTenant(() -> freightTemplateService.list());
        assertThat(all).extracting(FreightTemplateView::id).contains(templateId);
        FreightTemplateView fromList = all.stream().filter(view -> view.id().equals(templateId))
                .findFirst().orElseThrow();
        assertThat(fromList.rules()).hasSize(2);
    }
}
