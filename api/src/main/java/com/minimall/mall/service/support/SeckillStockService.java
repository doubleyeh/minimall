package com.minimall.mall.service.support;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.domain.MallSeckillActivity;
import com.minimall.mall.domain.MallSeckillBuyer;
import com.minimall.mall.domain.MallSeckillItem;
import com.minimall.mall.domain.MallStockLog;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallSeckillActivityRepository;
import com.minimall.mall.domain.repository.MallSeckillBuyerRepository;
import com.minimall.mall.domain.repository.MallSeckillItemRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.domain.repository.MallStockLogRepository;
import com.minimall.infra.id.SnowflakeIdGenerator;
import com.minimall.infra.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 秒杀库存与配额的唯一入口(商城设计文档 3.12)。
 *
 * <p>配额的收支只有两个方向:{@link #consume} 与 {@link #releaseItem}。支付、退款、售后都不改
 * {@code sold_count} —— 它统计的是"已占用的名额",不是"已付款件数",所以那几条路径不用为秒杀开分支。
 *
 * <p><b>与 Redis 的关系</b>:{@link SeckillRedisGate} 已经在流量入口挡过一次,这里再做一次
 * 数据库级别的精确判断。Redis 只影响"有多少请求能走到这里",不影响"最终卖了多少"。
 */
@Component
public class SeckillStockService {

    private static final Logger log = LoggerFactory.getLogger(SeckillStockService.class);

    private static final int STOCK_FREEZE = 6;
    private static final int STOCK_UNFREEZE = 7;

    /** 预热 key 的保留时间:活动结束后再留一天,给"最后一分钟下单"的请求兜底。 */
    private static final Duration KEY_TTL_BUFFER = Duration.ofDays(1);

    private final MallSeckillItemRepository itemRepository;
    private final MallSeckillActivityRepository activityRepository;
    private final MallSeckillBuyerRepository buyerRepository;
    private final MallSkuRepository skuRepository;
    private final MallStockLogRepository stockLogRepository;
    private final SkuStockKeeper stockKeeper;
    private final SeckillRedisGate gate;

    public SeckillStockService(MallSeckillItemRepository itemRepository,
                               MallSeckillActivityRepository activityRepository,
                               MallSeckillBuyerRepository buyerRepository,
                               MallSkuRepository skuRepository,
                               MallStockLogRepository stockLogRepository,
                               SkuStockKeeper stockKeeper,
                               SeckillRedisGate gate) {
        this.itemRepository = itemRepository;
        this.activityRepository = activityRepository;
        this.buyerRepository = buyerRepository;
        this.skuRepository = skuRepository;
        this.stockLogRepository = stockLogRepository;
        this.stockKeeper = stockKeeper;
        this.gate = gate;
    }

    /** 活动开始:把每个商品项的"还能抢多少"写进 Redis。 */
    public void prewarm(MallSeckillActivity activity) {
        Duration ttl = ttlOf(activity);
        for (MallSeckillItem item : itemRepository.findByActivityIdOrderBySortOrderAscIdAsc(activity.getId())) {
            gate.prewarm(item.getId(), item.remaining(), ttl);
        }
    }

    /** 活动结束/取消:清掉闸门。已购计数靠 TTL 自然过期,不需要逐个删。 */
    public void clearGate(MallSeckillActivity activity) {
        for (MallSeckillItem item : itemRepository.findByActivityIdOrderBySortOrderAscIdAsc(activity.getId())) {
            gate.clear(item.getId());
        }
    }

    /** 闸门未预热时的兜底:用数据库的剩余量补一次。 */
    public void ensureGateLoaded(MallSeckillItem item, MallSeckillActivity activity) {
        if (gate.remaining(item.getId()) != null) {
            return;
        }
        gate.loadIfAbsent(item.getId(), item.remaining(), ttlOf(activity));
    }

    public Duration ttlOf(MallSeckillActivity activity) {
        Duration left = Duration.between(LocalDateTime.now(), activity.getEndTime()).plus(KEY_TTL_BUFFER);
        return left.compareTo(KEY_TTL_BUFFER) < 0 ? KEY_TTL_BUFFER : left;
    }

    /**
     * 占用一个秒杀名额并锁库存(下单事务内调用)。
     *
     * <p>顺序不能换:先锁限购账本行,再占配额。反过来的话,两个人可能同时通过限购检查。
     */
    public void consume(MallSeckillItem item, Long customerId, int quantity) {
        Long tenantId = item.getTenantId();
        buyerRepository.ensureExists(SnowflakeIdGenerator.nextId(), tenantId, item.getId(), customerId);
        MallSeckillBuyer buyer = buyerRepository.findForUpdate(item.getId(), customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "秒杀限购账本缺失,请重试"));

        int limit = item.getPerCustomerLimit() == null ? 1 : item.getPerCustomerLimit();
        int bought = buyer.getBoughtCount() == null ? 0 : buyer.getBoughtCount();
        if (bought + quantity > limit) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "每人限购 " + limit + " 件,你已购 " + bought + " 件");
        }

        if (itemRepository.consumeQuota(item.getId(), tenantId, quantity) == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "已抢光,下次早点来");
        }
        if (buyerRepository.increase(buyer.getId(), quantity, limit) == 0) {
            // 行锁下不可能发生,除非账本被外部改过;抛出去让整单回滚,配额也随之释放
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "限购校验冲突,请重试");
        }
        if (stockKeeper.lockStockForSeckill(item.getSkuId(), tenantId, quantity) == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "秒杀库存已不足");
        }
        writeStockLog(item.getSkuId(), STOCK_FREEZE, 0, -quantity, item.getActivityId(), "秒杀下单占用");
    }

    /**
     * 订单关闭(取消/超时)时回退这笔占用的名额与库存。
     *
     * <p>活动仍在进行中:锁定退回冻结区,名额重新可抢。
     * 活动已结束:只退锁定,货回到普通可售(冻结在结束时已经全部解冻)。
     */
    public void releaseItem(Long tenantId, Long skuId, Long goodsId, Long seckillItemId, Long customerId,
                            int quantity, Long orderId) {
        MallSeckillItem item = itemRepository.findById(seckillItemId).orElse(null);
        if (item == null) {
            log.warn("秒杀订单关闭时找不到商品项,只退普通锁定 orderId={} itemId={}", orderId, seckillItemId);
            stockKeeper.releaseLockedStock(skuId, goodsId, tenantId, quantity);
            return;
        }
        MallSeckillActivity activity = activityRepository.findById(item.getActivityId()).orElse(null);
        boolean running = activity != null && activity.getStatus() != null
                && activity.getStatus() == MallSeckillActivity.STATUS_RUNNING;

        if (running) {
            if (stockKeeper.releaseLockedStockToFrozen(skuId, tenantId, quantity) == 0) {
                log.warn("秒杀订单关闭:锁定退回冻结失败(可能已释放) orderId={} skuId={}", orderId, skuId);
            }
            gate.restore(item.getId(), customerId, quantity);
        } else {
            stockKeeper.releaseLockedStock(skuId, goodsId, tenantId, quantity);
        }
        itemRepository.releaseQuota(item.getId(), tenantId, quantity);
        buyerRepository.findForUpdate(item.getId(), customerId)
                .ifPresent(buyer -> buyerRepository.decrease(buyer.getId(), quantity));
        writeStockLog(skuId, STOCK_UNFREEZE, 0, -quantity, item.getActivityId(), "秒杀订单关闭");
    }

    /**
     * 订单已关闭、钱又到账:按"货已卖出"处理。
     *
     * <p>关单时已经退过一次锁定与名额,这里要把名额重新占回来;占不回来(被别人抢走)就只扣实物库存 ——
     * 少收钱不能变成发出不存在的货。活动已结束时冻结早已解冻,只扣实物库存。
     */
    public void reopen(Long seckillItemId, Long customerId, int quantity, Long orderId) {
        MallSeckillItem item = itemRepository.findById(seckillItemId).orElse(null);
        if (item == null) {
            log.error("秒杀单复活时找不到商品项,需人工核对 orderId={} itemId={}", orderId, seckillItemId);
            return;
        }
        Long tenantId = item.getTenantId();
        MallSeckillActivity activity = activityRepository.findById(item.getActivityId()).orElse(null);
        boolean running = activity != null && activity.getStatus() != null
                && activity.getStatus() == MallSeckillActivity.STATUS_RUNNING;

        boolean quotaRestored = itemRepository.consumeQuota(item.getId(), tenantId, quantity) == 1;
        if (quotaRestored) {
            buyerRepository.ensureExists(SnowflakeIdGenerator.nextId(), tenantId, item.getId(), customerId);
            buyerRepository.findForUpdate(item.getId(), customerId)
                    .ifPresent(buyer -> buyerRepository.increase(buyer.getId(), quantity,
                            Integer.MAX_VALUE / 2));
        }
        int frozenMoved = 0;
        if (running && quotaRestored) {
            frozenMoved = stockKeeper.deductStockOnlyForSeckill(item.getSkuId(), item.getGoodsId(),
                    tenantId, quantity);
        }
        if (frozenMoved == 0) {
            stockKeeper.deductStockOnly(item.getSkuId(), item.getGoodsId(), tenantId, quantity);
        }
        writeStockLog(item.getSkuId(), 3, -quantity, -frozenMoved, orderId, "秒杀单复活扣减库存");
    }

    /** 发布时冻结一项配额。条件不满足(可售不够)直接抛,由调用方整单回滚。 */
    public void freezeItem(Long activityId, MallSeckillItem item) {
        if (stockKeeper.freezeStock(item.getSkuId(), item.getTenantId(), item.getSeckillStock()) == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "「" + item.getSkuId() + "」可售库存不足,无法冻结 " + item.getSeckillStock() + " 件");
        }
        writeStockLog(item.getSkuId(), STOCK_FREEZE, 0, item.getSeckillStock(), activityId, "发布活动冻结");
    }

    /** 活动结束/取消:把没卖掉的配额解冻回普通可售。 */
    public void unfreezeRemaining(MallSeckillActivity activity) {
        Long tenantId = activity.getTenantId();
        for (MallSeckillItem item : itemRepository.findByActivityIdOrderBySortOrderAscIdAsc(activity.getId())) {
            int remaining = item.remaining();
            if (remaining <= 0) {
                continue;
            }
            if (stockKeeper.unfreezeStock(item.getSkuId(), tenantId, remaining) == 0) {
                log.warn("解冻秒杀配额失败(可能已被解冻) itemId={} 数量={}", item.getId(), remaining);
                continue;
            }
            writeStockLog(item.getSkuId(), STOCK_UNFREEZE, 0, -remaining, activity.getId(), "活动结束解冻");
        }
    }

    /** 发布前的库存校验:冻结会不会把可售算成负数。返回 null 表示通过,否则是不可冻结的原因。 */
    public String checkFreezable(MallSeckillItem item) {
        MallSku sku = skuRepository.findById(item.getSkuId()).orElse(null);
        if (sku == null) {
            return "SKU 不存在";
        }
        if (sku.getStatus() == null || sku.getStatus() != 1) {
            return "「" + sku.getSkuName() + "」已停售";
        }
        if (sku.availableStock() < item.getSeckillStock()) {
            return "「" + sku.getSkuName() + "」可售仅 " + sku.availableStock() + " 件,不够冻结 "
                    + item.getSeckillStock() + " 件";
        }
        return null;
    }

    /** 场次清单变了:清掉客户端列表的短期缓存。 */
    public void clearHomeCache() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId != null) {
            gate.clearHome(tenantId);
        }
    }

    /** 某个 SKU 上是否已有待开始/进行中的秒杀(商品维护的护栏用)。 */
    public List<MallSeckillItem> lockedItemsOf(Long skuId) {
        return itemRepository.findLockedBySku(skuId);
    }

    public boolean isLocked(Long skuId) {
        return !lockedItemsOf(skuId).isEmpty();
    }

    private void writeStockLog(Long skuId, int changeType, int changeStock, int changeFrozen,
                               Long bizId, String remark) {
        MallStockLog entry = new MallStockLog();
        entry.setSkuId(skuId);
        entry.setChangeType(changeType);
        entry.setChangeStock(changeStock);
        entry.setChangeLocked(0);
        entry.setChangeFrozen(changeFrozen);
        entry.setBizId(bizId);
        entry.setRemark(remark);
        stockLogRepository.save(entry);
    }
}
