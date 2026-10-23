package com.minimall.mall.service.impl;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.api.dto.SeckillActivitySaveRequest;
import com.minimall.mall.api.dto.SeckillActivityView;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallSeckillActivity;
import com.minimall.mall.domain.MallSeckillItem;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.QMallSeckillActivity;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallSeckillActivityRepository;
import com.minimall.mall.domain.repository.MallSeckillItemRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.service.SeckillService;
import com.minimall.mall.service.support.SeckillStockService;
import com.querydsl.core.BooleanBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 秒杀活动管理实现(商城设计文档 3.12)。
 *
 * <p><b>发布是唯一会动库存的地方</b>:草稿阶段只存配置,取消/结束才解冻。把冻结放在发布这一步,
 * 是为了让"配置到一半把货锁了"这种事故不会发生。
 *
 * <p>状态迁移全部用条件更新(受影响行数为 0 就算冲突),所以多实例同时到点、运维手动重跑
 * 都不会重复冻结或重复解冻。
 */
@Service
@Transactional
public class SeckillServiceImpl implements SeckillService {

    private static final Logger log = LoggerFactory.getLogger(SeckillServiceImpl.class);

    private final MallSeckillActivityRepository activityRepository;
    private final MallSeckillItemRepository itemRepository;
    private final MallGoodsRepository goodsRepository;
    private final MallSkuRepository skuRepository;
    private final SeckillStockService stockService;

    public SeckillServiceImpl(MallSeckillActivityRepository activityRepository,
                              MallSeckillItemRepository itemRepository,
                              MallGoodsRepository goodsRepository,
                              MallSkuRepository skuRepository,
                              SeckillStockService stockService) {
        this.activityRepository = activityRepository;
        this.itemRepository = itemRepository;
        this.goodsRepository = goodsRepository;
        this.skuRepository = skuRepository;
        this.stockService = stockService;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<SeckillActivityView> page(String keyword, Integer status, int pageNo, int pageSize) {
        QMallSeckillActivity q = QMallSeckillActivity.mallSeckillActivity;
        BooleanBuilder where = new BooleanBuilder();
        if (keyword != null && !keyword.isBlank()) {
            where.and(q.activityName.contains(keyword));
        }
        if (status != null) {
            where.and(q.status.eq(status));
        }
        Page<MallSeckillActivity> page = activityRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1))
                        .withSort(Sort.by("id").descending()));
        List<SeckillActivityView> views = toViews(page.getContent());
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    @Transactional(readOnly = true)
    public SeckillActivityView detail(Long activityId) {
        MallSeckillActivity activity = load(activityId);
        return toViews(List.of(activity)).get(0);
    }

    @Override
    public Long create(SeckillActivitySaveRequest request) {
        validateBasics(request);
        MallSeckillActivity activity = new MallSeckillActivity();
        activity.setStatus(MallSeckillActivity.STATUS_DRAFT);
        applyFields(activity, request);
        activity = activityRepository.save(activity);
        saveItems(activity.getId(), request);
        return activity.getId();
    }

    @Override
    public void update(Long activityId, SeckillActivitySaveRequest request) {
        MallSeckillActivity activity = load(activityId);
        requireDraft(activity, "只有草稿可以编辑");
        validateBasics(request);
        applyFields(activity, request);
        itemRepository.deleteAll(itemRepository.findByActivityIdOrderBySortOrderAscIdAsc(activityId));
        saveItems(activityId, request);
    }

    @Override
    public void delete(Long activityId) {
        MallSeckillActivity activity = load(activityId);
        // 草稿与已取消(取消时会解冻)可以删;发布过的活动留着做历史,不能删
        Integer status = activity.getStatus();
        if (status == null || (status != MallSeckillActivity.STATUS_DRAFT
                && status != MallSeckillActivity.STATUS_CANCELLED)) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "只有草稿或已取消的活动可以删除");
        }
        itemRepository.deleteAll(itemRepository.findByActivityIdOrderBySortOrderAscIdAsc(activityId));
        activityRepository.delete(activity);
    }

    @Override
    public int publish(Long activityId) {
        MallSeckillActivity activity = load(activityId);
        requireDraft(activity, "只有草稿可以发布");
        List<MallSeckillItem> items = itemRepository.findByActivityIdOrderBySortOrderAscIdAsc(activityId);
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "至少需要一个秒杀商品");
        }
        LocalDateTime now = LocalDateTime.now();
        if (!activity.getStartTime().isBefore(activity.getEndTime())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "结束时间必须晚于开始时间");
        }
        if (!activity.getEndTime().isAfter(now)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "结束时间已过,不能发布");
        }

        Set<Long> seenSkus = new HashSet<>();
        for (MallSeckillItem item : items) {
            MallSku sku = skuRepository.findById(item.getSkuId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SKU 不存在"));
            if (!seenSkus.add(item.getSkuId())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "同一个 SKU 不能重复出现在一场活动里");
            }
            if (item.getSeckillPrice() == null || item.getSeckillPrice().compareTo(BigDecimal.ZERO) < 0
                    || item.getSeckillPrice().compareTo(sku.getPrice()) >= 0) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "「" + sku.getSkuName() + "」的秒杀价不能为负、且必须低于原价");
            }
            if (item.getPerCustomerLimit() == null || item.getPerCustomerLimit() < 1
                    || item.getPerCustomerLimit() > item.getSeckillStock()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "「" + sku.getSkuName() + "」的每人限购必须在 1 到配额之间");
            }
            if (activityRepository.countOverlapping(item.getSkuId(), activity.getStartTime(),
                    activity.getEndTime()) > 0) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "「" + sku.getSkuName() + "」已有时间重叠的秒杀活动");
            }
            String blocked = stockService.checkFreezable(item);
            if (blocked != null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, blocked);
            }
        }

        if (activityRepository.markPending(activityId, activity.getTenantId()) == 0) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "活动状态已变化,请刷新后重试");
        }
        int frozen = 0;
        for (MallSeckillItem item : items) {
            stockService.freezeItem(activityId, item);
            frozen += item.getSeckillStock();
        }
        stockService.clearHomeCache();
        log.info("秒杀活动已发布 activityId={} 商品项={} 冻结={}", activityId, items.size(), frozen);
        return frozen;
    }

    @Override
    public void cancel(Long activityId) {
        MallSeckillActivity activity = load(activityId);
        int status = activity.getStatus() == null ? -1 : activity.getStatus();
        if (status == MallSeckillActivity.STATUS_ENDED || status == MallSeckillActivity.STATUS_CANCELLED) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "活动已结束或已取消");
        }
        boolean frozen = status == MallSeckillActivity.STATUS_PENDING
                || status == MallSeckillActivity.STATUS_RUNNING;
        if (activityRepository.markCancelled(activityId, activity.getTenantId()) == 0) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "活动状态已变化,请刷新后重试");
        }
        if (frozen) {
            stockService.unfreezeRemaining(activity);
            stockService.clearGate(activity);
        }
        stockService.clearHomeCache();
        log.info("秒杀活动已取消 activityId={} 是否解冻={}", activityId, frozen);
    }

    @Override
    public int startDue() {
        LocalDateTime now = LocalDateTime.now();
        int started = 0;
        for (MallSeckillActivity activity : activityRepository.findDueToStart(now)) {
            if (activityRepository.markRunning(activity.getId(), activity.getTenantId(), now) == 0) {
                continue;
            }
            stockService.prewarm(activity);
            started++;
        }
        if (started > 0) {
            stockService.clearHomeCache();
        }
        return started;
    }

    @Override
    public int endDue() {
        LocalDateTime now = LocalDateTime.now();
        int ended = 0;
        for (MallSeckillActivity activity : activityRepository.findDueToEnd(now)) {
            if (activityRepository.markEnded(activity.getId(), activity.getTenantId(), now) == 0) {
                continue;
            }
            stockService.unfreezeRemaining(activity);
            stockService.clearGate(activity);
            ended++;
        }
        if (ended > 0) {
            stockService.clearHomeCache();
        }
        return ended;
    }

    // ------------------------------------------------------------------ 内部

    private MallSeckillActivity load(Long activityId) {
        return activityRepository.findById(activityId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "秒杀活动不存在"));
    }

    private void requireDraft(MallSeckillActivity activity, String message) {
        if (activity.getStatus() == null || activity.getStatus() != MallSeckillActivity.STATUS_DRAFT) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, message);
        }
    }

    private void validateBasics(SeckillActivitySaveRequest request) {
        if (request.startTime() == null || request.endTime() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择活动起止时间");
        }
        if (!request.startTime().isBefore(request.endTime())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "结束时间必须晚于开始时间");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "至少需要一个秒杀商品");
        }
        for (SeckillActivitySaveRequest.Item item : request.items()) {
            if (item.seckillStock() == null || item.seckillStock() < 1) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "配额至少为 1 件");
            }
            if (item.perCustomerLimit() == null || item.perCustomerLimit() < 1) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "每人限购至少为 1 件");
            }
        }
    }

    private void applyFields(MallSeckillActivity activity, SeckillActivitySaveRequest request) {
        activity.setActivityName(request.activityName());
        activity.setStartTime(request.startTime());
        activity.setEndTime(request.endTime());
        activity.setRemark(request.remark());
        activity.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
    }

    private void saveItems(Long activityId, SeckillActivitySaveRequest request) {
        Long tenantId = TenantContext.getTenantId();
        List<MallSeckillItem> items = new ArrayList<>();
        int index = 0;
        for (SeckillActivitySaveRequest.Item requestItem : request.items()) {
            MallSku sku = skuRepository.findById(requestItem.skuId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "SKU 不存在"));
            MallSeckillItem item = new MallSeckillItem();
            item.setActivityId(activityId);
            item.setGoodsId(sku.getGoodsId());
            item.setSkuId(sku.getId());
            item.setSeckillPrice(requestItem.seckillPrice());
            item.setSeckillStock(requestItem.seckillStock());
            item.setSoldCount(0);
            item.setPerCustomerLimit(requestItem.perCustomerLimit());
            item.setSortOrder(requestItem.sortOrder() == null ? index : requestItem.sortOrder());
            items.add(item);
            index++;
        }
        itemRepository.saveAll(items);
        if (tenantId == null) {
            log.warn("保存秒杀商品项时没有租户上下文 activityId={}", activityId);
        }
    }

    private List<SeckillActivityView> toViews(List<MallSeckillActivity> activities) {
        if (activities.isEmpty()) {
            return List.of();
        }
        List<Long> activityIds = activities.stream().map(MallSeckillActivity::getId).toList();
        Map<Long, List<MallSeckillItem>> itemsByActivity = new HashMap<>();
        itemRepository.findByActivityIdInOrderBySortOrderAscIdAsc(activityIds)
                .forEach(item -> itemsByActivity.computeIfAbsent(item.getActivityId(), key -> new ArrayList<>())
                        .add(item));

        Map<Long, MallGoods> goodsById = new HashMap<>();
        Map<Long, MallSku> skuById = new HashMap<>();
        itemsByActivity.values().forEach(list -> list.forEach(item -> {
            goodsById.computeIfAbsent(item.getGoodsId(), id -> goodsRepository.findById(id).orElse(null));
            skuById.computeIfAbsent(item.getSkuId(), id -> skuRepository.findById(id).orElse(null));
        }));

        List<SeckillActivityView> views = new ArrayList<>();
        for (MallSeckillActivity activity : activities) {
            List<MallSeckillItem> items = itemsByActivity.getOrDefault(activity.getId(), List.of());
            List<SeckillActivityView.Item> itemViews = items.stream()
                    .map(item -> toItemView(item, goodsById.get(item.getGoodsId()), skuById.get(item.getSkuId())))
                    .toList();
            int quota = items.stream().mapToInt(MallSeckillItem::getSeckillStock).sum();
            int sold = items.stream().mapToInt(item -> item.getSoldCount() == null ? 0 : item.getSoldCount()).sum();
            boolean frozenPhase = activity.getStatus() != null
                    && (activity.getStatus() == MallSeckillActivity.STATUS_PENDING
                    || activity.getStatus() == MallSeckillActivity.STATUS_RUNNING);
            views.add(new SeckillActivityView(activity.getId(), activity.getActivityName(),
                    activity.getStartTime(), activity.getEndTime(), activity.getStatus(), activity.getRemark(),
                    activity.getSortOrder(), quota, sold, frozenPhase ? quota - sold : 0, itemViews));
        }
        return views;
    }

    private SeckillActivityView.Item toItemView(MallSeckillItem item, MallGoods goods, MallSku sku) {
        return new SeckillActivityView.Item(item.getId(), item.getGoodsId(),
                goods == null ? null : goods.getGoodsName(),
                goods == null ? null : goods.getMainImage(),
                item.getSkuId(),
                sku == null ? null : sku.getSkuCode(),
                sku == null ? null : sku.getSkuName(),
                sku == null ? null : sku.getPrice(),
                item.getSeckillPrice(), item.getSeckillStock(), item.getSoldCount(),
                item.getPerCustomerLimit(), item.remaining());
    }
}
