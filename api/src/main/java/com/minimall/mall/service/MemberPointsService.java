package com.minimall.mall.service;

import java.math.BigDecimal;

/**
 * 会员积分与成长值(商城设计文档 3.4)。
 *
 * <p>积分与成长值是两个独立的量:积分可消耗、可按批次过期,成长值只受退款扣回与滚动窗口影响。
 * 等级由**近 N 个月滚动成长值**判定,所以窗口滚出老值时会降级。
 *
 * <p>所有方法都必须在租户上下文中调用(定时任务走 {@code TenantTaskRunner})。
 */
public interface MemberPointsService {

    /**
     * 确认收货发放:按实付金额向下取整发放等额积分与成长值,并建一个积分批次。
     *
     * <p>幂等 —— 同一订单只会发一次(确认收货有手动与自动两条路径,都会调到这里)。
     *
     * @return 实际发放的数量;0 表示没发(已发过,或实付不足 1 元)
     */
    int grant(Long customerId, Long orderId, BigDecimal payAmount);

    /**
     * 抵现预扣:扣减汇总余额并按 FIFO 消耗批次,同时记录占用了哪些批次以便原样退回。
     *
     * @return 实际扣减的积分数
     * @throws com.minimall.common.BusinessException 余额不足(被并发用掉)
     */
    int redeem(Long customerId, Long orderId, int points);

    /**
     * 订单关闭退回预扣的积分。已过期的批次不退回(那部分该随过期清零,退回等于凭空续期)。
     *
     * <p>退回后删掉占用记录,所以重复取消不会二次退回。
     *
     * @return 实际退回的积分数
     */
    int returnForOrder(Long orderId, Long customerId);

    /**
     * 该客户当前**可用**积分(抵现上限与展示都以它为准)。
     *
     * <p>会先把该客户已到期的批次清零(懒过期),所以返回值是精确的 ——
     * 不依赖过期任务是否跑过。
     */
    int usablePoints(Long customerId);

    /**
     * 过期清理(定时任务,逐租户):把已到期批次清零并写流水。
     *
     * <p>只处理不活跃客户即可 —— 活跃客户在下单时已被 {@link #usablePoints} 懒过期。
     *
     * @param limit 单轮上限;剩余的下轮继续(扫描条件天然推进)
     * @return 处理过的客户数
     */
    int expireBatches(int limit);

    /**
     * 滚动成长值重算与等级刷新(定时任务,逐租户):这是**降级的唯一来源**。
     *
     * <p>范围是有成长值记录的客户。不做增量筛选:漏跑一次任务就会漏掉那天的降级,
     * 而漏掉的客户从此不会被重算。多算是幂等的,漏算不是。
     *
     * @return 重算的客户数
     */
    int refreshRollingGrowth();

    /** 管理端手动调整:正数建批次,负数按 FIFO 扣减且扣到 0 为止。 */
    void manualAdjust(Long customerId, Integer pointsDelta, Integer growthDelta, String remark);

    /**
     * 售后退款扣回(3.11):按 {@code 退款金额 / 订单实付金额} 的占比扣回该订单发放的积分与成长值,
     * **扣到 0 为止**(不允许负积分)。
     *
     * <p>三条规则都在这里,调用方只说"哪个售后单退了多少钱":
     * <ul>
     *   <li><b>换货不扣</b> —— 钱没退,交易仍然成立;</li>
     *   <li><b>按比例</b> —— 一单可以有多笔部分退款,每次按退款占比扣,累计不超过发放值;</li>
     *   <li><b>幂等</b> —— 同一售后单只扣一次。</li>
     * </ul>
     *
     * <p>未确认收货就退款时该订单还没发过积分,直接跳过。
     *
     * @param refundAmount 本次退款金额;订单实付为 0 或不详时按全额处理
     * @return 实际扣回的积分数
     */
    int clawBack(Long afterSaleId, Long orderId, Long customerId, BigDecimal refundAmount, int afterSaleType);
}
