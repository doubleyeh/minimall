package com.minimall.mall.service.impl;

import com.minimall.api.mall.dto.FreightTemplateSaveRequest;
import com.minimall.api.mall.dto.FreightTemplateView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.domain.MallFreightTemplate;
import com.minimall.mall.domain.MallFreightTemplateRule;
import com.minimall.mall.domain.repository.MallFreightTemplateRepository;
import com.minimall.mall.domain.repository.MallFreightTemplateRuleRepository;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.service.FreightTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 运费模板实现(商城设计文档 3.7)。
 */
@Service
@Transactional
public class FreightTemplateServiceImpl implements FreightTemplateService {

    private final MallFreightTemplateRepository templateRepository;
    private final MallFreightTemplateRuleRepository ruleRepository;
    private final MallGoodsRepository goodsRepository;

    public FreightTemplateServiceImpl(MallFreightTemplateRepository templateRepository,
                                      MallFreightTemplateRuleRepository ruleRepository,
                                      MallGoodsRepository goodsRepository) {
        this.templateRepository = templateRepository;
        this.ruleRepository = ruleRepository;
        this.goodsRepository = goodsRepository;
    }

    @Override
    public List<FreightTemplateView> list() {
        return templateRepository.findByOrderByIdAsc().stream()
                .map(template -> new FreightTemplateView(template.getId(), template.getTemplateName(),
                        template.getChargeType(), rulesOf(template.getId())))
                .toList();
    }

    @Override
    public FreightTemplateView detail(Long templateId) {
        MallFreightTemplate template = load(templateId);
        return new FreightTemplateView(template.getId(), template.getTemplateName(),
                template.getChargeType(), rulesOf(templateId));
    }

    @Override
    public Long create(FreightTemplateSaveRequest request) {
        validateRules(request);
        if (templateRepository.existsByTemplateName(request.templateName())) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "模板名称已存在");
        }
        MallFreightTemplate template = new MallFreightTemplate();
        template.setTemplateName(request.templateName());
        template.setChargeType(request.chargeType());
        template = templateRepository.save(template);
        saveRules(template.getId(), request.rules());
        return template.getId();
    }

    @Override
    public void update(Long templateId, FreightTemplateSaveRequest request) {
        MallFreightTemplate template = load(templateId);
        validateRules(request);
        if (templateRepository.existsByTemplateNameAndIdNot(request.templateName(), templateId)) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "模板名称已存在");
        }
        template.setTemplateName(request.templateName());
        template.setChargeType(request.chargeType());
        // 规则全量替换:管理端提交的就是最终形态,逐条 diff 只会增加出错面
        ruleRepository.deleteByTemplateId(templateId);
        saveRules(templateId, request.rules());
    }

    @Override
    public void delete(Long templateId) {
        MallFreightTemplate template = load(templateId);
        if (goodsRepository.existsByFreightTemplateId(templateId)) {
            // 静默解绑会让这些商品突然变成"包邮",商家少收的运费不会有任何提示
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "仍有商品使用该运费模板,请先调整商品");
        }
        ruleRepository.deleteByTemplateId(templateId);
        templateRepository.delete(template);
    }

    /**
     * 校验规则集合。
     *
     * <p>三件事:①必须有一条 {@code ALL} 兜底(否则未配置省份算不出运费);
     * ②区域不能重复(两条同区域规则时,"取第一条命中的"取决于查询顺序,结果不可预期);
     * ③续件步长必须大于 0(步长为 0 会让"续件数量"除零)。
     */
    private void validateRules(FreightTemplateSaveRequest request) {
        List<FreightTemplateSaveRequest.Rule> rules = request.rules();
        boolean hasFallback = rules.stream()
                .anyMatch(rule -> rule.region() == null || rule.region().isBlank()
                        || MallFreightTemplateRule.REGION_ALL.equals(rule.region().trim()));
        if (!hasFallback) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "必须配置一条不限区域(ALL)的兜底规则,否则部分省份无法计算运费");
        }
        long distinctRegions = rules.stream().map(rule -> normalizeRegion(rule.region())).distinct().count();
        if (distinctRegions != rules.size()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "存在重复的适用区域");
        }
        for (FreightTemplateSaveRequest.Rule rule : rules) {
            if (rule.additionalUnit().signum() <= 0) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "续件/续重步长必须大于 0");
            }
        }
    }

    private void saveRules(Long templateId, List<FreightTemplateSaveRequest.Rule> rules) {
        for (FreightTemplateSaveRequest.Rule ruleRequest : rules) {
            MallFreightTemplateRule rule = new MallFreightTemplateRule();
            rule.setTemplateId(templateId);
            rule.setRegion(normalizeRegion(ruleRequest.region()));
            rule.setFirstUnit(ruleRequest.firstUnit());
            rule.setFirstFee(ruleRequest.firstFee());
            rule.setAdditionalUnit(ruleRequest.additionalUnit());
            rule.setAdditionalFee(ruleRequest.additionalFee());
            rule.setFreeShippingAmount(ruleRequest.freeShippingAmount());
            ruleRepository.save(rule);
        }
    }

    private String normalizeRegion(String region) {
        if (region == null || region.isBlank()) {
            return MallFreightTemplateRule.REGION_ALL;
        }
        String trimmed = region.trim();
        return MallFreightTemplateRule.REGION_ALL.equalsIgnoreCase(trimmed)
                ? MallFreightTemplateRule.REGION_ALL : trimmed;
    }

    private List<FreightTemplateView.Rule> rulesOf(Long templateId) {
        return ruleRepository.findByTemplateIdOrderByIdAsc(templateId).stream()
                .map(rule -> new FreightTemplateView.Rule(rule.getId(), rule.getRegion(), rule.getFirstUnit(),
                        rule.getFirstFee(), rule.getAdditionalUnit(), rule.getAdditionalFee(),
                        rule.getFreeShippingAmount()))
                .toList();
    }

    private MallFreightTemplate load(Long templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "运费模板不存在"));
    }
}
