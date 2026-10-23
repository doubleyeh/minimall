package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.api.dto.ClientGoodsSeckillView;
import com.minimall.mall.api.dto.ClientSeckillHomeView;
import com.minimall.mall.api.dto.ClientSeckillItemView;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallSeckillActivity;
import com.minimall.mall.domain.MallSeckillItem;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallSeckillActivityRepository;
import com.minimall.mall.domain.repository.MallSeckillBuyerRepository;
import com.minimall.mall.domain.repository.MallSeckillItemRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.service.support.SeckillRedisGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 小程序端秒杀浏览(商城设计文档 3.12)。
 *
 * <p><b>剩余量优先读 Redis</b>:详情页会被反复刷新,每次都查数据库等于把秒杀流量压到库上。
 * Redis 没有(活动刚开始、Redis 重启)才回退数据库,回退值本身就是预热用的同一个口径。
 *
 * <p>"进行中 / 即将开始"由服务端按自己的时钟分组 —— 端上时钟不准会出现"显示进行中却抢不了"。
 */
@Service
@Transactional(readOnly = true)
public class SeckillClientService {

    private final MallSeckillActivityRepository activityRepository;
    private final MallSeckillItemRepository itemRepository;
    private final MallSeckillBuyerRepository buyerRepository;
    private final MallGoodsRepository goodsRepository;
    private final MallSkuRepository skuRepository;
    private final SeckillRedisGate gate;

    private static final Logger log = LoggerFactory.getLogger(SeckillClientService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SeckillClientService(MallSeckillActivityRepository activityRepository,
                                MallSeckillItemRepository itemRepository,
                                MallSeckillBuyerRepository buyerRepository,
                                MallGoodsRepository goodsRepository,
                                MallSkuRepository skuRepository,
                                SeckillRedisGate gate) {
        this.activityRepository = activityRepository;
        this.itemRepository = itemRepository;
        this.buyerRepository = buyerRepository;
        this.goodsRepository = goodsRepository;
        this.skuRepository = skuRepository;
        this.gate = gate;
    }

    public ClientSeckillHomeView home() {
        Long tenantId = TenantContext.getTenantId();
        String cached = gate.readHome(tenantId);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, ClientSeckillHomeView.class);
            } catch (RuntimeException ex) {
                // 缓存解不开就当没缓存,走数据库重算。不能因为缓存把列表也拖挂
                log.warn("秒杀列表缓存解析失败,回源重算", ex);
            }
        }
        ClientSeckillHomeView view = loadHome();
        try {
            gate.putHome(tenantId, objectMapper.writeValueAsString(view));
        } catch (RuntimeException ex) {
            log.warn("秒杀列表缓存写入失败(不影响结果)", ex);
        }
        return view;
    }

    private ClientSeckillHomeView loadHome() {
        LocalDateTime now = LocalDateTime.now();
        List<MallSeckillActivity> activities = activityRepository.findVisible(now);
        if (activities.isEmpty()) {
            return new ClientSeckillHomeView(List.of(), List.of());
        }
        Map<Long, MallSeckillActivity> activityById = new HashMap<>();
        activities.forEach(activity -> activityById.put(activity.getId(), activity));
        List<MallSeckillItem> items = itemRepository.findByActivityIdInOrderBySortOrderAscIdAsc(
                activities.stream().map(MallSeckillActivity::getId).toList());

        Map<Long, MallGoods> goodsById = new HashMap<>();
        Map<Long, MallSku> skuById = new HashMap<>();
        for (MallSeckillItem item : items) {
            goodsById.computeIfAbsent(item.getGoodsId(), id -> goodsRepository.findById(id).orElse(null));
            skuById.computeIfAbsent(item.getSkuId(), id -> skuRepository.findById(id).orElse(null));
        }

        List<ClientSeckillItemView> running = new ArrayList<>();
        List<ClientSeckillItemView> upcoming = new ArrayList<>();
        for (MallSeckillItem item : items) {
            MallSeckillActivity activity = activityById.get(item.getActivityId());
            if (activity == null) {
                continue;
            }
            ClientSeckillItemView view = toView(item, activity, goodsById.get(item.getGoodsId()),
                    skuById.get(item.getSkuId()), 0);
            if (activity.getStatus() != null && activity.getStatus() == MallSeckillActivity.STATUS_RUNNING) {
                running.add(view);
            } else {
                upcoming.add(view);
            }
        }
        return new ClientSeckillHomeView(running, upcoming);
    }

    public ClientSeckillItemView detail(Long itemId) {
        MallSeckillItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "秒杀商品不存在"));
        MallSeckillActivity activity = activityRepository.findById(item.getActivityId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "秒杀活动不存在"));
        MallGoods goods = goodsRepository.findById(item.getGoodsId()).orElse(null);
        MallSku sku = skuRepository.findById(item.getSkuId()).orElse(null);
        return toView(item, activity, goods, sku, boughtOf(item.getId()));
    }

    /** 商品详情页的秒杀入口:该商品任一 SKU 上有进行中/待开始的活动就返回第一条。 */
    public ClientGoodsSeckillView seckillOfGoods(Long goodsId) {
        return itemRepository.findVisibleByGoods(goodsId, LocalDateTime.now()).stream()
                .findFirst()
                .map(item -> {
                    MallSeckillActivity activity = activityRepository.findById(item.getActivityId()).orElse(null);
                    Integer cached = gate.remaining(item.getId());
                    return new ClientGoodsSeckillView(item.getId(), item.getSkuId(), item.getSeckillPrice(),
                            cached == null ? item.remaining() : Math.max(cached, 0),
                            item.getPerCustomerLimit(),
                            activity == null ? null : activity.getStartTime(),
                            activity == null ? null : activity.getEndTime(),
                            activity == null ? null : activity.getStatus());
                })
                .orElse(null);
    }

    private int boughtOf(Long itemId) {
        Long customerId = ClientContext.getCustomerId();
        if (customerId == null) {
            return 0;
        }
        return buyerRepository.findByItemIdAndCustomerId(itemId, customerId)
                .map(buyer -> buyer.getBoughtCount() == null ? 0 : buyer.getBoughtCount())
                .orElse(0);
    }

    private ClientSeckillItemView toView(MallSeckillItem item, MallSeckillActivity activity,
                                         MallGoods goods, MallSku sku, int bought) {
        Integer cached = gate.remaining(item.getId());
        int remaining = cached == null ? item.remaining() : Math.max(cached, 0);
        return new ClientSeckillItemView(item.getId(), item.getActivityId(), activity.getActivityName(),
                item.getGoodsId(),
                goods == null ? null : goods.getGoodsName(),
                sku != null && sku.getSkuImage() != null ? sku.getSkuImage()
                        : (goods == null ? null : goods.getMainImage()),
                item.getSkuId(), sku == null ? null : sku.getSkuName(),
                sku == null ? null : sku.getPrice(), item.getSeckillPrice(),
                item.getSeckillStock(), item.getSoldCount(), remaining, item.getPerCustomerLimit(),
                bought, activity.getStartTime(), activity.getEndTime(), activity.getStatus());
    }
}
