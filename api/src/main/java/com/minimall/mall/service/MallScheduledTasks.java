package com.minimall.mall.service;

import com.minimall.mall.service.MarketingMaintenanceService;
import com.minimall.mall.service.MemberPointsService;
import com.minimall.mall.service.OrderService;
import com.minimall.mall.infra.pay.WxPayClient;
import com.minimall.mall.service.support.WxPayRefundSubmitter;
import com.minimall.sys.service.support.DictIntReader;
import com.minimall.infra.schedule.ScheduledTaskLock;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.service.support.TenantTaskRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 商城定时任务(商城设计文档第 4 节)。
 *
 * <p>三个设计要点:
 * <ol>
 *   <li><b>逐租户执行</b>:全部交给 {@link TenantTaskRunner}。它保证每个租户在自己的
 *       {@code TenantContext} + 独立事务里跑,单个租户失败不影响其它租户(6.2)。
 *       不这么做的话,任务要么读到全部租户的数据(危险),要么在"没有租户上下文"下查不到任何数据(静默失效)</li>
 *   <li><b>阈值来自字典而不是硬编码</b>(3.4、3.9):超时时间是最常被运营要求调整的参数,
 *       写死意味着每次改都要发版。字典读取失败时退回默认值,不让配置问题把任务整个卡住</li>
 *   <li><b>操作人统一记平台超管</b>:系统任务的 {@code operator_id} 需要有值(否则日志里无法区分
 *       "人做的"和"系统做的"),这里用固定的系统操作人 ID,并在流水备注里写清是系统行为</li>
 * </ol>
 *
 * <p>四个任务都先抢 Redis 锁再执行(多实例部署时同一批数据只能被处理一遍,见架构文档 6.2),
 * 抢不到锁就整个跳过。
 */
@Component
public class MallScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(MallScheduledTasks.class);

    /** 系统操作人:平台超管。仅用于审计字段(create_by/update_by/operator_id)。 */
    private static final long SYSTEM_ACTOR_ID = 1L;

    private static final int DEFAULT_PAY_TIMEOUT_MINUTES = 15;
    private static final int DEFAULT_AUTO_RECEIVE_DAYS = 15;
    private static final int DEFAULT_AFTER_SALE_MERCHANT_HOURS = 72;
    private static final int DEFAULT_AFTER_SALE_BUYER_DAYS = 7;
    private static final int DEFAULT_AFTER_SALE_RECEIVE_DAYS = 10;

    /** 积分过期单轮上限。剩余的下轮继续 —— 扫描条件是 remain>0 且已过期,天然推进。 */
    private static final int EXPIRE_BATCH_LIMIT = 500;

    /** 关单后仍可能到账的窗口:买家在超时边缘付款,回调只会晚几秒到几分钟。 */
    private static final Duration RECONCILE_WINDOW = Duration.ofMinutes(15);

    /** 退款多快没受理才算"没提交成功":太短会把正常在途的申请重投一遍。 */
    private static final Duration REFUND_RETRY_STALE = Duration.ofMinutes(10);

    private static final String DICT_PAY_TIMEOUT = "order_pay_timeout_minutes";
    private static final String DICT_AUTO_RECEIVE = "order_auto_receive_days";
    private static final String DICT_AFTER_SALE_TIMEOUT = "after_sale_timeout";

    private final TenantTaskRunner tenantTaskRunner;
    private final OrderService orderService;
    private final AfterSaleService afterSaleService;
    private final MarketingMaintenanceService marketingMaintenanceService;
    private final DictIntReader dictIntReader;
    private final MemberPointsService memberPointsService;
    private final PayService payService;
    private final WxPayClient wxPayClient;
    private final WxPayRefundSubmitter refundSubmitter;
    private final ScheduledTaskLock taskLock;

    public MallScheduledTasks(TenantTaskRunner tenantTaskRunner,
                              OrderService orderService,
                              AfterSaleService afterSaleService,
                              MarketingMaintenanceService marketingMaintenanceService,
                              DictIntReader dictIntReader,
                              MemberPointsService memberPointsService,
                              PayService payService,
                              WxPayClient wxPayClient,
                              WxPayRefundSubmitter refundSubmitter,
                              ScheduledTaskLock taskLock) {
        this.tenantTaskRunner = tenantTaskRunner;
        this.orderService = orderService;
        this.afterSaleService = afterSaleService;
        this.marketingMaintenanceService = marketingMaintenanceService;
        this.dictIntReader = dictIntReader;
        this.memberPointsService = memberPointsService;
        this.payService = payService;
        this.wxPayClient = wxPayClient;
        this.refundSubmitter = refundSubmitter;
        this.taskLock = taskLock;
    }

    /** 订单超时关闭(每分钟)。 */
    @Scheduled(cron = "${minimall.schedule.cron.close-timeout-orders:0 * * * * ?}")
    public void closeTimeoutOrders() {
        // 多实例部署时每个实例都会触发:MallScheduledTasks 的四个任务都要抢锁,抢不到就整个跳过
        taskLock.runIfNotLocked("关闭超时未支付订单", () -> {
            int minutes = dictIntReader.get(DICT_PAY_TIMEOUT, DEFAULT_PAY_TIMEOUT_MINUTES);
            LocalDateTime deadline = LocalDateTime.now().minusMinutes(minutes);
            var result = tenantTaskRunner.runForEachTenant("关闭超时未支付订单", SYSTEM_ACTOR_ID,
                    tenantId -> {
                        int closed = orderService.closeTimeoutOrders(deadline);
                        if (closed > 0) {
                            log.info("租户 {} 关闭超时未支付订单 {} 笔", tenantId, closed);
                        }
                    });
            failIfPartial("关闭超时未支付订单", result);
        });
    }

    /** 订单自动确认收货(每小时)。 */
    @Scheduled(cron = "${minimall.schedule.cron.auto-receive-orders:0 0 * * * ?}")
    public void autoReceiveOrders() {
        taskLock.runIfNotLocked("订单自动确认收货", () -> {
            int days = dictIntReader.get(DICT_AUTO_RECEIVE, DEFAULT_AUTO_RECEIVE_DAYS);
            LocalDateTime deadline = LocalDateTime.now().minusDays(days);
            var result = tenantTaskRunner.runForEachTenant("订单自动确认收货", SYSTEM_ACTOR_ID,
                    tenantId -> {
                        int finished = orderService.autoReceiveOrders(deadline);
                        if (finished > 0) {
                            log.info("租户 {} 自动确认收货 {} 笔", tenantId, finished);
                        }
                    });
            failIfPartial("订单自动确认收货", result);
        });
    }

    /**
     * 售后超时处理(每小时):一次跑完三条规则,因为它们同属"售后卡在某个环节太久"。
     *
     * <p>三个阈值都来自字典 {@code after_sale_timeout}(72 小时 / 7 天 / 10 天),
     * 而不是硬编码 —— 售后时效是最常被业务方要求调整的参数(3.9)。
     */
    @Scheduled(cron = "${minimall.schedule.cron.after-sale-timeout:0 15 * * * ?}")
    public void handleAfterSaleTimeout() {
        taskLock.runIfNotLocked("售后超时处理", () -> {
            // 三档阈值存在同一个字典类型下(按标签区分),这里按标签取值的顺序与 V4 种子数据一致:
            // 1-商家处理(小时) 2-买家退货(天) 3-商家收货(天)
            int merchantHours = dictIntReader.getAt(DICT_AFTER_SALE_TIMEOUT, 0, DEFAULT_AFTER_SALE_MERCHANT_HOURS);
            int buyerDays = dictIntReader.getAt(DICT_AFTER_SALE_TIMEOUT, 1, DEFAULT_AFTER_SALE_BUYER_DAYS);
            int receiveDays = dictIntReader.getAt(DICT_AFTER_SALE_TIMEOUT, 2, DEFAULT_AFTER_SALE_RECEIVE_DAYS);
            LocalDateTime now = LocalDateTime.now();
            var result = tenantTaskRunner.runForEachTenant("售后超时处理", SYSTEM_ACTOR_ID, tenantId -> {
                int autoApproved = afterSaleService.autoApproveTimeout(now.minusHours(merchantHours));
                int closed = afterSaleService.autoCloseTimeout(now.minusDays(buyerDays));
                int autoReceived = afterSaleService.autoReceiveTimeout(now.minusDays(receiveDays));
                if (autoApproved + closed + autoReceived > 0) {
                    log.info("租户 {} 售后超时处理:自动同意 {} / 自动关闭 {} / 自动收货 {}",
                            tenantId, autoApproved, closed, autoReceived);
                }
            });
            failIfPartial("售后超时处理", result);
        });
    }

    /** 优惠券过期清理(每天 3:30,避开业务高峰)。 */
    @Scheduled(cron = "${minimall.schedule.cron.expire-coupon-records:0 30 3 * * ?}")
    public void expireCouponRecords() {
        taskLock.runIfNotLocked("优惠券过期清理", () -> {
            var result = tenantTaskRunner.runForEachTenant("优惠券过期清理", SYSTEM_ACTOR_ID,
                    tenantId -> {
                        int expired = marketingMaintenanceService.expireOutdatedCouponRecords();
                        if (expired > 0) {
                            log.info("租户 {} 过期优惠券清理 {} 条", tenantId, expired);
                        }
                    });
            failIfPartial("优惠券过期清理", result);
        });
    }

    /**
     * 积分过期清零(每天 4:00)。
     *
     * <p>只兜底不活跃客户:活跃客户在下单算抵现上限时已经懒过期过了。
     *
     * @see MemberPointsService#expireBatches(int)
     */
    @Scheduled(cron = "${minimall.schedule.cron.expire-points:0 0 4 * * ?}")
    public void expirePoints() {
        taskLock.runIfNotLocked("积分过期清零", () -> {
            var result = tenantTaskRunner.runForEachTenant("积分过期清零", SYSTEM_ACTOR_ID,
                    tenantId -> {
                        int handled = memberPointsService.expireBatches(EXPIRE_BATCH_LIMIT);
                        if (handled > 0) {
                            log.info("租户 {} 积分过期清零涉及 {} 个客户", tenantId, handled);
                        }
                    });
            failIfPartial("积分过期清零", result);
        });
    }

    /**
     * 会员等级重算(每天 4:20):成长值按滚动窗口重算,够不着门槛的会降级。
     *
     * <p>这是**降级的唯一来源** —— 发放/扣回/手动调整都是即时重算,只有"窗口滚出老值"
     * 这件事没有业务动作可依附。
     */
    @Scheduled(cron = "${minimall.schedule.cron.recompute-growth-levels:0 20 4 * * ?}")
    public void recomputeGrowthLevels() {
        taskLock.runIfNotLocked("会员等级重算", () -> {
            var result = tenantTaskRunner.runForEachTenant("会员等级重算", SYSTEM_ACTOR_ID,
                    tenantId -> {
                        int count = memberPointsService.refreshRollingGrowth();
                        if (count > 0) {
                            log.info("租户 {} 会员等级重算 {} 个客户", tenantId, count);
                        }
                    });
            failIfPartial("会员等级重算", result);
        });
    }

    /**
     * 核对已关闭订单的支付状态(每 5 分钟)。
     *
     * <p>迟到的回调会把订单置回待发货(见 PayServiceImpl.markPaid),但**回调丢了**时没有第二个人知道:
     * 钱收了、单还关着。这里扫刚关闭的订单主动查单,查到已支付就补上。
     *
     * <p>查单是出网调用,所以拆成"事务内取候选 → 事务外逐笔查单 → 事务内落库"三步,不在事务里等网络(3.3)。
     */
    @Scheduled(cron = "${minimall.schedule.cron.reconcile-closed-paid-orders:0 */5 * * * ?}")
    public void reconcileClosedPaidOrders() {
        taskLock.runIfNotLocked("核对已关闭订单支付状态", () -> {
            LocalDateTime closedAfter = LocalDateTime.now().minus(RECONCILE_WINDOW);
            List<PayService.ClosedUnpaidOrder> candidates = new ArrayList<>();
            var result = tenantTaskRunner.runForEachTenant("核对已关闭订单支付状态", SYSTEM_ACTOR_ID,
                    tenantId -> candidates.addAll(payService.listRecentlyClosedUnpaid(closedAfter)));
            failIfPartial("核对已关闭订单支付状态", result);

            int settled = 0;
            for (PayService.ClosedUnpaidOrder candidate : candidates) {
                if (settleIfPaid(candidate)) {
                    settled++;
                }
            }
            if (settled > 0) {
                log.warn("查单补回 {} 笔「已关闭但已支付」的订单", settled);
            }
        });
    }

    /**
     * 事务外查单;确认已支付才进租户上下文落库(每笔各自一个事务)。
     *
     * <p>单笔失败只记日志、不进 failed 租户:它下一轮还会被扫到,不会丢。
     */
    private boolean settleIfPaid(PayService.ClosedUnpaidOrder candidate) {
        WxPayClient.QueryResult query;
        try {
            query = wxPayClient.queryOrder(candidate.tenantId(), candidate.outTradeNo()).orElse(null);
        } catch (RuntimeException ex) {
            log.error("查单失败,本轮跳过 outTradeNo={}", candidate.outTradeNo(), ex);
            return false;
        }
        if (query == null || !WxPayClient.TRADE_STATE_SUCCESS.equals(query.tradeState())) {
            return false;
        }
        try {
            TenantContext.callAsTenant(candidate.tenantId(), false, () -> {
                payService.settleClosedPaidOrder(candidate.orderId(), query.transactionId(),
                        "定时查单:订单已关闭但支付成功");
                return null;
            });
        } catch (RuntimeException ex) {
            log.error("补回已支付订单失败 orderId={}", candidate.orderId(), ex);
            return false;
        }
        log.warn("订单已关闭但钱已收到,已置回待发货 orderId={} outTradeNo={}",
                candidate.orderId(), candidate.outTradeNo());
        return true;
    }

    /**
     * 退款提交重试(每 10 分钟)。
     *
     * <p>退款申请是事务提交后异步发的,那一步失败时退款单会停在"提交失败"、或者一直挂在"申请中"
     * 却没有微信退款单号 —— 不重投就永远没人管,买家收不到钱也没有回调来收尾。
     *
     * <p>微信按 {@code out_refund_no} 幂等,重投不会重复退款。
     */
    @Scheduled(cron = "${minimall.schedule.cron.retry-refund-submit:0 */10 * * * ?}")
    public void retryRefundSubmit() {
        taskLock.runIfNotLocked("退款提交重试", () -> {
            LocalDateTime staleBefore = LocalDateTime.now().minus(REFUND_RETRY_STALE);
            List<PayService.RetryableRefund> refunds = new ArrayList<>();
            var result = tenantTaskRunner.runForEachTenant("退款提交重试", SYSTEM_ACTOR_ID,
                    tenantId -> refunds.addAll(payService.listRetryableRefunds(staleBefore)));
            failIfPartial("退款提交重试", result);

            for (PayService.RetryableRefund refund : refunds) {
                // 提交本身把结果写回退款单(见 WxPayRefundSubmitter),这里不需要再判断成败
                refundSubmitter.submitAfterCommit(refund.tenantId(), refund.orderId(),
                        refund.refundId(), refund.refundAmount(), "退款提交重试");
            }
            if (!refunds.isEmpty()) {
                log.info("重投退款申请 {} 笔", refunds.size());
            }
        });
    }

    /**
     * 有租户失败就抛出:执行历史按"任务有没有抛异常"记结果,只 warn 会把"部分租户没处理"记成成功。
     *
     * <p>{@link TenantTaskRunner#runForEachTenant} 把单租户异常吞在循环里(6.2),到这里只剩一串失败租户 ID。
     */
    private void failIfPartial(String taskName, TenantTaskRunner.RunResult result) {
        if (result.failedTenantIds() == null || result.failedTenantIds().isEmpty()) {
            return;
        }
        throw new IllegalStateException("任务「" + taskName + "」部分租户执行失败,该批次需重跑:" + result.failedTenantIds());
    }
}
