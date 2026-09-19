package com.minimall.mall.service.impl;

import com.minimall.mall.api.dto.PromotionSaveRequest;
import com.minimall.mall.api.dto.PromotionView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.mall.domain.MallPromotionFullReduction;
import com.minimall.mall.domain.MallPromotionFullReductionScope;
import com.minimall.mall.domain.QMallPromotionFullReduction;
import com.minimall.mall.domain.repository.MallPromotionFullReductionRepository;
import com.minimall.mall.domain.repository.MallPromotionFullReductionScopeRepository;
import com.minimall.mall.service.PromotionService;
import com.minimall.mall.service.support.OrderAmountCalculator;
import com.querydsl.core.BooleanBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 满减活动实现(商城设计文档 3.5、3.10)。
 *
 * <p><b>保存时就用计算器解析一遍规则</b>:这是"配置错误在保存时就暴露"的关键一步。
 * 规则 JSON 写坏时计算器会按"不减免"兜底(下单不会崩),但如果等到用户下单才发现
 * "活动配了却从来没生效",运营会以为功能坏了。所以这里用一个明显的金额做一次试算:
 * 只要规则里至少有一档能命中,就说明格式正确。
 */
@Service
@Transactional
public class PromotionServiceImpl implements PromotionService {

    private static final int SCOPE_ALL = MallPromotionFullReduction.SCOPE_ALL;
    private static final int STATUS_ENABLED = 1;

    private final MallPromotionFullReductionRepository promotionRepository;
    private final MallPromotionFullReductionScopeRepository scopeRepository;
    private final OrderAmountCalculator calculator;

    public PromotionServiceImpl(MallPromotionFullReductionRepository promotionRepository,
                                MallPromotionFullReductionScopeRepository scopeRepository,
                                OrderAmountCalculator calculator) {
        this.promotionRepository = promotionRepository;
        this.scopeRepository = scopeRepository;
        this.calculator = calculator;
    }

    @Override
    public PageResult<PromotionView> page(String activityName, Integer status, int pageNo, int pageSize) {
        QMallPromotionFullReduction qPromotion = QMallPromotionFullReduction.mallPromotionFullReduction;
        BooleanBuilder where = new BooleanBuilder();
        if (activityName != null && !activityName.isBlank()) {
            where.and(qPromotion.activityName.contains(activityName));
        }
        if (status != null) {
            where.and(qPromotion.status.eq(status));
        }
        Page<MallPromotionFullReduction> page = promotionRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1))
                        .withSort(Sort.by("id").descending()));
        Map<Long, List<Long>> scopeIdsByActivity = new HashMap<>();
        List<Long> activityIds = page.getContent().stream().map(MallPromotionFullReduction::getId).toList();
        if (!activityIds.isEmpty()) {
            scopeRepository.findByActivityIdIn(activityIds)
                    .forEach(scope -> scopeIdsByActivity
                            .computeIfAbsent(scope.getActivityId(), key -> new ArrayList<>())
                            .add(scope.getScopeId()));
        }
        List<PromotionView> views = page.getContent().stream()
                .map(activity -> new PromotionView(activity.getId(), activity.getActivityName(),
                        activity.getReductionRule(), activity.getScopeType(),
                        scopeIdsByActivity.getOrDefault(activity.getId(), List.of()),
                        activity.getValidStartTime(), activity.getValidEndTime(), activity.getStatus()))
                .toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public Long create(PromotionSaveRequest request) {
        validate(request);
        MallPromotionFullReduction activity = new MallPromotionFullReduction();
        applyFields(activity, request);
        activity = promotionRepository.save(activity);
        saveScopes(activity.getId(), request);
        return activity.getId();
    }

    @Override
    public void update(Long activityId, PromotionSaveRequest request) {
        MallPromotionFullReduction activity = load(activityId);
        validate(request);
        applyFields(activity, request);
        // 范围全量替换:活动与它的适用范围是一体的,逐条 diff 只会增加出错面
        scopeRepository.deleteByActivityId(activityId);
        saveScopes(activityId, request);
    }

    @Override
    public void changeStatus(Long activityId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "状态只能是 0(停用)或 1(启用)");
        }
        MallPromotionFullReduction activity = load(activityId);
        activity.setStatus(status);
    }

    // ------------------------------------------------------------------ 内部

    private void validate(PromotionSaveRequest request) {
        if (request.validEndTime().isBefore(request.validStartTime())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "结束时间不能早于开始时间");
        }
        // 试算:任意一个门槛都能命中,说明 JSON 至少是"可用"的
        BigDecimal probe = calculator.reductionFor(request.reductionRule(), new BigDecimal("1000000"));
        if (probe.signum() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "满减规则无法解析出任何有效档位,请检查 JSON 格式(如 [{\"amount\":100,\"reduce\":10}])");
        }
        boolean scoped = request.scopeType() != null && request.scopeType() != SCOPE_ALL;
        if (scoped && (request.scopeIds() == null || request.scopeIds().isEmpty())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "指定分类/商品时至少需要选择一个范围");
        }
    }

    private void applyFields(MallPromotionFullReduction activity, PromotionSaveRequest request) {
        activity.setActivityName(request.activityName());
        activity.setReductionRule(request.reductionRule());
        activity.setScopeType(request.scopeType());
        activity.setValidStartTime(request.validStartTime());
        activity.setValidEndTime(request.validEndTime());
        activity.setStatus(request.status() == null ? STATUS_ENABLED : request.status());
    }

    private void saveScopes(Long activityId, PromotionSaveRequest request) {
        // 全部商品时范围表不记录(文档 3.10):留着多余的记录会让"这个活动到底作用于什么"变得含糊
        if (request.scopeType() == null || request.scopeType() == SCOPE_ALL || request.scopeIds() == null) {
            return;
        }
        request.scopeIds().stream().distinct().forEach(scopeId -> {
            MallPromotionFullReductionScope scope = new MallPromotionFullReductionScope();
            scope.setActivityId(activityId);
            scope.setScopeId(scopeId);
            scopeRepository.save(scope);
        });
    }

    private MallPromotionFullReduction load(Long activityId) {
        return promotionRepository.findById(activityId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "满减活动不存在"));
    }
}
