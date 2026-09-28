package com.minimall.mall.service.impl;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallAfterSale;
import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallGrowthLog;
import com.minimall.mall.domain.MallMemberLevel;
import com.minimall.mall.domain.MallPointsBatch;
import com.minimall.mall.domain.MallPointsLog;
import com.minimall.mall.domain.MallPointsUse;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import com.minimall.mall.domain.repository.MallGrowthLogRepository;
import com.minimall.mall.domain.repository.MallMemberLevelRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallPointsBatchRepository;
import com.minimall.mall.domain.repository.MallPointsLogRepository;
import com.minimall.mall.domain.repository.MallPointsUseRepository;
import com.minimall.mall.service.MemberPointsService;
import com.minimall.sys.service.support.DictIntReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会员积分与成长值实现(商城设计文档 3.4)。
 *
 * <p>三个必须守住的不变量,每个写路径都要成对维护:
 * <ol>
 *   <li>{@code mall_customer.points} == Σ(该客户未过期批次的剩余) —— 汇总与明细不能各走各的;</li>
 *   <li>积分的每次变动都有一条 {@code mall_points_log},且带上变动后余额;</li>
 *   <li>成长值只由 {@code mall_growth_log} 的滚动求和决定,不做增量累加
 *       (窗口已经滚过的客户做累加会算错)。</li>
 * </ol>
 *
 * <p>余额一律用条件 UPDATE 做相对增减,不"读出来改再写":后者在并发时会丢失另一笔变动。
 */
@Service
@Transactional
public class MemberPointsServiceImpl implements MemberPointsService {

    private static final Logger log = LoggerFactory.getLogger(MemberPointsServiceImpl.class);

    /** 1 元 = 1 积分 + 1 成长值。发放时向下取整(90.50 元 → 90),对商家保守且不产生"半分钱积分"。 */
    private static final BigDecimal EARN_PER_YUAN = BigDecimal.ONE;

    private static final String DICT_POINTS_EXPIRE_MONTHS = "points_expire_months";
    private static final int DEFAULT_POINTS_EXPIRE_MONTHS = 12;
    private static final String DICT_GROWTH_ROLL_MONTHS = "growth_roll_months";
    private static final int DEFAULT_GROWTH_ROLL_MONTHS = 12;

    private final MallCustomerRepository customerRepository;
    private final MallPointsLogRepository pointsLogRepository;
    private final MallPointsBatchRepository batchRepository;
    private final MallPointsUseRepository pointsUseRepository;
    private final MallGrowthLogRepository growthLogRepository;
    private final MallMemberLevelRepository levelRepository;
    private final MallOrderRepository orderRepository;
    private final DictIntReader dictIntReader;

    public MemberPointsServiceImpl(MallCustomerRepository customerRepository,
                                   MallPointsLogRepository pointsLogRepository,
                                   MallPointsBatchRepository batchRepository,
                                   MallPointsUseRepository pointsUseRepository,
                                   MallGrowthLogRepository growthLogRepository,
                                   MallMemberLevelRepository levelRepository,
                                   MallOrderRepository orderRepository,
                                   DictIntReader dictIntReader) {
        this.customerRepository = customerRepository;
        this.pointsLogRepository = pointsLogRepository;
        this.batchRepository = batchRepository;
        this.pointsUseRepository = pointsUseRepository;
        this.growthLogRepository = growthLogRepository;
        this.levelRepository = levelRepository;
        this.orderRepository = orderRepository;
        this.dictIntReader = dictIntReader;
    }

    // ---------------------------------------------------------------- 发放

    @Override
    public int grant(Long customerId, Long orderId, BigDecimal payAmount) {
        Long tenantId = requireTenantId();
        if (orderId == null) {
            throw new IllegalArgumentException("发放积分必须带订单 ID(它同时也是幂等键)");
        }
        if (pointsLogRepository.existsByBizTypeAndBizId(MallPointsLog.BIZ_GRANT, orderId)) {
            // 确认收货有手动与自动两条路径,重复触发是常态,这里直接返回而不是报错
            return 0;
        }
        int base = earnOf(payAmount);
        if (base <= 0) {
            // 实付不足 1 元(含 0 元订单):不发,也不留一条 0 的流水
            return 0;
        }

        customerRepository.addPoints(customerId, tenantId, base);
        MallPointsLog pointsLog = newLog(customerId, base, balanceOf(customerId, tenantId),
                MallPointsLog.BIZ_GRANT, orderId, null, "确认收货发放");
        pointsLogRepository.save(pointsLog);
        // 批次要记来源流水:上面的 addPoints 会清空持久化上下文,所以先存流水再建批次
        createBatch(customerId, base, pointsLog.getId());

        growthLogRepository.save(newGrowthLog(customerId, base, MallGrowthLog.BIZ_GRANT, orderId, null, "确认收货发放"));
        refreshRollingGrowth(customerId, tenantId);

        log.info("客户 {} 订单 {} 确认收货,发放 {} 积分与成长值", customerId, orderId, base);
        return base;
    }

    private int earnOf(BigDecimal payAmount) {
        if (payAmount == null || payAmount.signum() <= 0) {
            return 0;
        }
        return payAmount.multiply(EARN_PER_YUAN).setScale(0, RoundingMode.FLOOR).intValue();
    }

    /**
     * 建一个积分批次。过期时间 = 现在 + 字典 {@code points_expire_months} 个月。
     *
     * <p>天数按"发放时刻 + N 个月"算而不是"当天 23:59:59":后者会让同一天不同时刻发的积分
     * 过期时间完全一致,而"哪一批先过期"正是 FIFO 的依据。
     */
    private void createBatch(Long customerId, int points, Long sourceLogId) {
        int expireMonths = dictIntReader.get(DICT_POINTS_EXPIRE_MONTHS, DEFAULT_POINTS_EXPIRE_MONTHS);
        MallPointsBatch batch = new MallPointsBatch();
        batch.setCustomerId(customerId);
        batch.setSourceLogId(sourceLogId);
        batch.setTotalPoints(points);
        batch.setRemainPoints(points);
        batch.setExpireTime(LocalDateTime.now().plusMonths(expireMonths));
        batchRepository.save(batch);
    }

    // ---------------------------------------------------------------- 抵现

    @Override
    public int redeem(Long customerId, Long orderId, int points) {
        Long tenantId = requireTenantId();
        if (points <= 0) {
            return 0;
        }
        // 先用汇总余额当总闸门:扣不动说明确实不够,直接拒绝。
        // 反过来(先扣批次再发现余额不够)会留下"批次扣了、余额没扣"的不一致状态。
        if (customerRepository.deductPoints(customerId, tenantId, points) == 0) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "积分余额不足,请刷新后重试");
        }
        int consumed = consumeFifo(customerId, tenantId, points, orderId);
        if (consumed != points) {
            // 余额与批次对不上属于数据异常:整单回滚,不能放一个不一致的状态过去
            throw new IllegalStateException("积分批次与余额不一致:期望扣减 " + points + ",实际 " + consumed);
        }
        pointsLogRepository.save(newLog(customerId, -points, balanceOf(customerId, tenantId),
                MallPointsLog.BIZ_REDEEM, orderId, null, "下单积分抵现"));
        return points;
    }

    /**
     * 按 FIFO 消耗批次:先到期的先用,同过期时间按发放顺序。
     *
     * <p>每次扣减都是条件 UPDATE,受影响行数为 0 表示该批次被并发抢走,接着试下一批。
     *
     * @param orderId 非空时记录占用明细(用于订单关闭时原样退回);为空表示不需要退回
     * @return 实际扣减的积分数
     */
    private int consumeFifo(Long customerId, Long tenantId, int points, Long orderId) {
        int remaining = points;
        for (MallPointsBatch batch : batchRepository.findUsable(customerId, tenantId, LocalDateTime.now())) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(batch.getRemainPoints(), remaining);
            // consume 会清空持久化上下文,所以 take 要先算出来(不依赖实体还托管)
            if (batchRepository.consume(batch.getId(), tenantId, take) == 0) {
                continue;
            }
            if (orderId != null) {
                MallPointsUse use = new MallPointsUse();
                use.setOrderId(orderId);
                use.setBatchId(batch.getId());
                use.setPoints(take);
                pointsUseRepository.save(use);
            }
            remaining -= take;
        }
        return points - remaining;
    }

    // ---------------------------------------------------------------- 订单关闭退回

    @Override
    public int returnForOrder(Long orderId, Long customerId) {
        Long tenantId = requireTenantId();
        List<MallPointsUse> uses = pointsUseRepository.findByOrderIdAndTenantId(orderId, tenantId);
        if (uses.isEmpty()) {
            // 本来就没用积分,或已经退过(退回后删行)
            return 0;
        }
        // 先把要退的信息摘出来:下面的批次更新会清空持久化上下文,实体随即游离
        List<Returnable> plan = uses.stream()
                .map(use -> new Returnable(use.getId(), use.getBatchId(), use.getPoints()))
                .toList();

        LocalDateTime now = LocalDateTime.now();
        int returned = 0;
        for (Returnable item : plan) {
            if (batchRepository.returnToBatch(item.batchId(), tenantId, item.points(), now) > 0) {
                returned += item.points();
            } else {
                log.warn("订单 {} 退回积分时批次 {} 已过期,这部分不再退回", orderId, item.batchId());
            }
        }
        // 删占用行放在批次更新之后:删行就是"这笔占用已了结"的标记,重复取消查不到行、也就不会二次退回
        pointsUseRepository.deleteAllById(plan.stream().map(Returnable::useId).toList());

        if (returned > 0) {
            customerRepository.addPoints(customerId, tenantId, returned);
            pointsLogRepository.save(newLog(customerId, returned, balanceOf(customerId, tenantId),
                    MallPointsLog.BIZ_RETURN, orderId, null, "订单关闭退回积分"));
        }
        return returned;
    }

    private record Returnable(Long useId, Long batchId, int points) {
    }

    // ---------------------------------------------------------------- 过期

    @Override
    public int usablePoints(Long customerId) {
        Long tenantId = requireTenantId();
        expireForCustomer(customerId, tenantId, LocalDateTime.now());
        return balanceOf(customerId, tenantId);
    }

    @Override
    public int expireBatches(int limit) {
        Long tenantId = requireTenantId();
        List<MallPointsBatch> expired = batchRepository.findExpired(tenantId, LocalDateTime.now(),
                Pageable.ofSize(limit));
        if (expired.isEmpty()) {
            return 0;
        }
        // 按客户汇总:一个客户一条过期流水就够读,批次粒度已经在 mall_points_batch 里了
        Map<Long, List<ExpiredBatch>> byCustomer = new LinkedHashMap<>();
        for (MallPointsBatch batch : expired) {
            byCustomer.computeIfAbsent(batch.getCustomerId(), key -> new ArrayList<>())
                    .add(new ExpiredBatch(batch.getId(), batch.getRemainPoints()));
        }
        int handled = 0;
        for (Map.Entry<Long, List<ExpiredBatch>> entry : byCustomer.entrySet()) {
            int expiredPoints = clearBatches(entry.getKey(), tenantId, entry.getValue());
            if (expiredPoints > 0) {
                handled++;
            }
        }
        return handled;
    }

    /**
     * 懒过期:把该客户已到期的批次清掉。
     *
     * <p>值在于"可用积分"任何时刻都精确,不取决于过期任务跑没跑过 —— 否则用户会看到
     * 一个包含已过期积分的余额,下单时才发现不能用。
     */
    private int expireForCustomer(Long customerId, Long tenantId, LocalDateTime now) {
        List<MallPointsBatch> expired = batchRepository.findExpiredByCustomer(customerId, tenantId, now);
        if (expired.isEmpty()) {
            return 0;
        }
        return clearBatches(customerId, tenantId, expired.stream()
                .map(batch -> new ExpiredBatch(batch.getId(), batch.getRemainPoints()))
                .toList());
    }

    /** 清零批次并同步汇总余额,返回清零的积分总量。 */
    private int clearBatches(Long customerId, Long tenantId, List<ExpiredBatch> batches) {
        int expiredPoints = 0;
        for (ExpiredBatch batch : batches) {
            // 条件 remain_points > 0 保证幂等:重复运行不会把同一批重复计入
            if (batchRepository.clearRemain(batch.batchId(), tenantId) > 0) {
                expiredPoints += batch.points();
            }
        }
        if (expiredPoints <= 0) {
            return 0;
        }
        customerRepository.deductPointsClampToZero(customerId, tenantId, expiredPoints);
        pointsLogRepository.save(newLog(customerId, -expiredPoints, balanceOf(customerId, tenantId),
                MallPointsLog.BIZ_EXPIRE, null, null, "积分过期清零"));
        log.info("客户 {} 过期清零 {} 积分", customerId, expiredPoints);
        return expiredPoints;
    }

    private record ExpiredBatch(Long batchId, int points) {
    }

    // ---------------------------------------------------------------- 成长值与等级

    @Override
    public int refreshRollingGrowth() {
        Long tenantId = requireTenantId();
        List<Long> customerIds = growthLogRepository.findDistinctCustomerIds(tenantId);
        for (Long customerId : customerIds) {
            refreshRollingGrowth(customerId, tenantId);
        }
        return customerIds.size();
    }

    /**
     * 按流水重算滚动成长值并刷新等级。
     *
     * <p>重算而不是增量累加:客户可能已经一年没有动作,窗口早就把老值滚出去了,
     * 这时候在他的旧值上做加减会一直偏大。一次索引聚合的代价换来永远正确。
     */
    private void refreshRollingGrowth(Long customerId, Long tenantId) {
        int rollMonths = dictIntReader.get(DICT_GROWTH_ROLL_MONTHS, DEFAULT_GROWTH_ROLL_MONTHS);
        Long sum = growthLogRepository.sumSince(customerId, tenantId,
                LocalDateTime.now().minusMonths(rollMonths));
        int rolling = sum == null ? 0 : Math.max(sum.intValue(), 0);

        MallCustomer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalStateException("客户不存在 customerId=" + customerId));
        customer.setGrowthValue(rolling);
        customer.setMemberLevelId(matchLevelId(rolling, tenantId));
        customerRepository.save(customer);
    }

    /** 当前等级:门槛降序里第一个够得着的。没有等级定义时返回 null(应用层展示为"普通会员")。 */
    private Long matchLevelId(int growth, Long tenantId) {
        return levelRepository.findByStatusAndTenantIdOrderByGrowthThresholdDesc(1, tenantId).stream()
                .filter(level -> level.getGrowthThreshold() != null && growth >= level.getGrowthThreshold())
                .map(MallMemberLevel::getId)
                .findFirst()
                .orElse(null);
    }

    // ---------------------------------------------------------------- 管理端手动调整

    @Override
    public void manualAdjust(Long customerId, Integer pointsDelta, Integer growthDelta, String remark) {
        Long tenantId = requireTenantId();
        int pointsChange = pointsDelta == null ? 0 : pointsDelta;
        int growthChange = growthDelta == null ? 0 : growthDelta;
        if (pointsChange == 0 && growthChange == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "积分与成长值至少要调整一项");
        }

        int actualPointsChange;
        if (pointsChange > 0) {
            customerRepository.addPoints(customerId, tenantId, pointsChange);
            MallPointsLog pointsLog = newLog(customerId, pointsChange, balanceOf(customerId, tenantId),
                    MallPointsLog.BIZ_MANUAL, null, null, remark);
            pointsLogRepository.save(pointsLog);
            // 增加也要建批次:只加汇总余额的话,可用积分(按批次算)与余额立刻就对不上
            createBatch(customerId, pointsChange, pointsLog.getId());
            actualPointsChange = pointsChange;
        } else {
            // 扣到 0 为止,并把实际扣减量记进流水(否则流水之和与余额变化对不上)
            actualPointsChange = -consumeClampToZero(customerId, tenantId, -pointsChange);
            if (actualPointsChange != 0) {
                pointsLogRepository.save(newLog(customerId, actualPointsChange, balanceOf(customerId, tenantId),
                        MallPointsLog.BIZ_MANUAL, null, null, remark));
            }
        }

        if (growthChange != 0) {
            growthLogRepository.save(newGrowthLog(customerId, growthChange, MallGrowthLog.BIZ_MANUAL,
                    null, null, remark));
        }
        refreshRollingGrowth(customerId, tenantId);
        log.info("管理端调整客户 {} 积分 {} / 成长值 {}", customerId, actualPointsChange, growthChange);
    }

    // ---------------------------------------------------------------- 售后退款扣回

    @Override
    public int clawBack(Long afterSaleId, Long orderId, Long customerId, BigDecimal refundAmount,
                        int afterSaleType) {
        Long tenantId = requireTenantId();
        if (afterSaleType == MallAfterSale.TYPE_EXCHANGE) {
            // 换货没退钱,交易仍然成立
            return 0;
        }
        if (afterSaleId != null
                && pointsLogRepository.existsByBizTypeAndBizRefId(MallPointsLog.BIZ_CLAWBACK, afterSaleId)) {
            // 同一售后单只扣一次
            return 0;
        }

        int granted = sumOf(MallPointsLog.BIZ_GRANT, tenantId, customerId, orderId);
        if (granted <= 0) {
            // 还没确认收货就退款:这笔订单根本没发过积分
            return 0;
        }
        int remainingGranted = Math.max(granted - sumOf(MallPointsLog.BIZ_CLAWBACK, tenantId, customerId, orderId), 0);
        if (remainingGranted == 0) {
            // 多笔部分退款已经累计扣满发放值
            return 0;
        }

        int target = Math.min(proportionalClawback(granted, orderId, refundAmount), remainingGranted);
        int clawedPoints = consumeClampToZero(customerId, tenantId, target);
        if (clawedPoints > 0) {
            pointsLogRepository.save(newLog(customerId, -clawedPoints, balanceOf(customerId, tenantId),
                    MallPointsLog.BIZ_CLAWBACK, orderId, afterSaleId, "售后退款扣回"));
        }

        // 成长值按同一个"应扣量"扣,而不是按实际扣到的积分:积分可能已经被花掉或过期,
        // 但成长值代表历史贡献,该降还是要降(扣到 0 为止)
        int growthTarget = Math.min(target, growthOf(customerId, tenantId));
        if (growthTarget > 0) {
            customerRepository.deductGrowthClampToZero(customerId, tenantId, growthTarget);
            growthLogRepository.save(newGrowthLog(customerId, -growthTarget, MallGrowthLog.BIZ_CLAWBACK,
                    orderId, afterSaleId, "售后退款扣回"));
        }
        refreshRollingGrowth(customerId, tenantId);

        log.info("售后退款扣回 tenantId={} afterSaleId={} orderId={} 积分 {} 成长值 {}",
                tenantId, afterSaleId, orderId, clawedPoints, growthTarget);
        return clawedPoints;
    }

    /**
     * 应扣量 = 发放量 × (退款金额 / 订单实付金额),向下取整。
     *
     * <p>分母用订单实付而不是退款行金额:一单可有多笔部分退款,用实付做分母才能让
     * "各笔占比之和 == 1"在整单退光时成立。
     */
    private int proportionalClawback(int granted, Long orderId, BigDecimal refundAmount) {
        BigDecimal payAmount = orderRepository.findById(orderId)
                .map(MallOrder::getPayAmount)
                .orElse(null);
        if (payAmount == null || payAmount.signum() <= 0 || refundAmount == null) {
            // 实付为 0(全额优惠)或不详:按全额扣,不给"退光钱还留着积分"的口子
            return granted;
        }
        BigDecimal ratio = refundAmount.divide(payAmount, 6, RoundingMode.HALF_UP).min(BigDecimal.ONE);
        return BigDecimal.valueOf(granted).multiply(ratio).setScale(0, RoundingMode.FLOOR).intValue();
    }

    /** 按 (类型, 订单) 汇总变动量并取绝对值:发放是正数、扣回是负数。 */
    private int sumOf(int bizType, Long tenantId, Long customerId, Long orderId) {
        Long sum = pointsLogRepository.sumChangePoints(tenantId, customerId, bizType, orderId);
        return sum == null ? 0 : Math.abs(sum.intValue());
    }

    private int growthOf(Long customerId, Long tenantId) {
        return customerRepository.findById(customerId)
                .map(MallCustomer::getGrowthValue)
                .orElseThrow(() -> new IllegalStateException(
                        "客户不存在或不属于当前租户 customerId=" + customerId + " tenantId=" + tenantId));
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 扣减积分与批次,**扣到 0 为止**(退款扣回、过期清零、手动调减共用)。
     *
     * <p>先夹取到当前余额再扣,并让汇总余额与实际扣到的批次数量一致 ——
     * 不一致时以批次为准(余额只是缓存)。
     *
     * @return 实际扣减的积分数
     */
    private int consumeClampToZero(Long customerId, Long tenantId, int desired) {
        if (desired <= 0) {
            return 0;
        }
        int target = Math.min(desired, balanceOf(customerId, tenantId));
        if (target <= 0) {
            return 0;
        }
        int consumed = consumeFifo(customerId, tenantId, target, null);
        if (consumed != target) {
            log.error("客户 {} 的积分余额与批次可用量不一致:余额 {},期望扣 {},实际扣到 {}",
                    customerId, target, target, consumed);
        }
        if (consumed > 0) {
            customerRepository.deductPointsClampToZero(customerId, tenantId, consumed);
        }
        return consumed;
    }

    // ---------------------------------------------------------------- 内部

    private MallPointsLog newLog(Long customerId, int changePoints, int balancePoints, int bizType,
                                 Long bizId, Long bizRefId, String remark) {
        MallPointsLog pointsLog = new MallPointsLog();
        pointsLog.setCustomerId(customerId);
        pointsLog.setChangePoints(changePoints);
        pointsLog.setBalancePoints(balancePoints);
        pointsLog.setBizType(bizType);
        pointsLog.setBizId(bizId);
        pointsLog.setBizRefId(bizRefId);
        pointsLog.setRemark(remark);
        return pointsLog;
    }

    private MallGrowthLog newGrowthLog(Long customerId, int changeGrowth, int bizType,
                                       Long bizId, Long bizRefId, String remark) {
        MallGrowthLog growthLog = new MallGrowthLog();
        growthLog.setCustomerId(customerId);
        growthLog.setChangeGrowth(changeGrowth);
        growthLog.setBizType(bizType);
        growthLog.setBizId(bizId);
        growthLog.setBizRefId(bizRefId);
        growthLog.setRemark(remark);
        return growthLog;
    }

    /**
     * 读变动后的余额。
     *
     * <p>必须在条件 UPDATE **之后**调用:那些更新带 {@code clearAutomatically = true},
     * 会清空持久化上下文,所以这里一定是重新查库而不是读缓存中的实体。
     */
    private int balanceOf(Long customerId, Long tenantId) {
        return customerRepository.findById(customerId)
                .map(MallCustomer::getPoints)
                .orElseThrow(() -> new IllegalStateException(
                        "客户不存在或不属于当前租户 customerId=" + customerId + " tenantId=" + tenantId));
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("积分操作必须在租户上下文中执行(定时任务请走 TenantTaskRunner)");
        }
        return tenantId;
    }
}
