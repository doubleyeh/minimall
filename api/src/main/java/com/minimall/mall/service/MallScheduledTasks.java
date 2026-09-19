package com.minimall.mall.service;

import com.minimall.sys.api.dto.DictItemView;
import com.minimall.mall.service.MarketingMaintenanceService;
import com.minimall.mall.service.OrderService;
import com.minimall.sys.service.DictService;
import com.minimall.sys.service.support.TenantTaskRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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
 * <p>注意:售后相关的三个超时任务还没实现 —— 售后流程本身尚未开发(见待办),
 * 补上售后后在这里加对应任务即可,骨架与下面这些完全一致。
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

    private static final String DICT_PAY_TIMEOUT = "order_pay_timeout_minutes";
    private static final String DICT_AUTO_RECEIVE = "order_auto_receive_days";
    private static final String DICT_AFTER_SALE_TIMEOUT = "after_sale_timeout";

    private final TenantTaskRunner tenantTaskRunner;
    private final OrderService orderService;
    private final AfterSaleService afterSaleService;
    private final MarketingMaintenanceService marketingMaintenanceService;
    private final DictService dictService;

    public MallScheduledTasks(TenantTaskRunner tenantTaskRunner,
                              OrderService orderService,
                              AfterSaleService afterSaleService,
                              MarketingMaintenanceService marketingMaintenanceService,
                              DictService dictService) {
        this.tenantTaskRunner = tenantTaskRunner;
        this.orderService = orderService;
        this.afterSaleService = afterSaleService;
        this.marketingMaintenanceService = marketingMaintenanceService;
        this.dictService = dictService;
    }

    /** 订单超时关闭(每分钟)。 */
    @Scheduled(cron = "0 * * * * ?")
    public void closeTimeoutOrders() {
        int minutes = dictInt(DICT_PAY_TIMEOUT, DEFAULT_PAY_TIMEOUT_MINUTES);
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(minutes);
        var result = tenantTaskRunner.runForEachTenant("关闭超时未支付订单", SYSTEM_ACTOR_ID,
                tenantId -> {
                    int closed = orderService.closeTimeoutOrders(deadline);
                    if (closed > 0) {
                        log.info("租户 {} 关闭超时未支付订单 {} 笔", tenantId, closed);
                    }
                });
        logIfFailed("关闭超时未支付订单", result);
    }

    /** 订单自动确认收货(每小时)。 */
    @Scheduled(cron = "0 0 * * * ?")
    public void autoReceiveOrders() {
        int days = dictInt(DICT_AUTO_RECEIVE, DEFAULT_AUTO_RECEIVE_DAYS);
        LocalDateTime deadline = LocalDateTime.now().minusDays(days);
        var result = tenantTaskRunner.runForEachTenant("订单自动确认收货", SYSTEM_ACTOR_ID,
                tenantId -> {
                    int finished = orderService.autoReceiveOrders(deadline);
                    if (finished > 0) {
                        log.info("租户 {} 自动确认收货 {} 笔", tenantId, finished);
                    }
                });
        logIfFailed("订单自动确认收货", result);
    }

    /**
     * 售后超时处理(每小时):一次跑完三条规则,因为它们同属"售后卡在某个环节太久"。
     *
     * <p>三个阈值都来自字典 {@code after_sale_timeout}(72 小时 / 7 天 / 10 天),
     * 而不是硬编码 —— 售后时效是最常被业务方要求调整的参数(3.9)。
     */
    @Scheduled(cron = "0 15 * * * ?")
    public void handleAfterSaleTimeout() {
        // 三档阈值存在同一个字典类型下(按标签区分),这里按标签取值的顺序与 V4 种子数据一致:
        // 1-商家处理(小时) 2-买家退货(天) 3-商家收货(天)
        int merchantHours = dictIntAt(DICT_AFTER_SALE_TIMEOUT, 0, DEFAULT_AFTER_SALE_MERCHANT_HOURS);
        int buyerDays = dictIntAt(DICT_AFTER_SALE_TIMEOUT, 1, DEFAULT_AFTER_SALE_BUYER_DAYS);
        int receiveDays = dictIntAt(DICT_AFTER_SALE_TIMEOUT, 2, DEFAULT_AFTER_SALE_RECEIVE_DAYS);
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
        logIfFailed("售后超时处理", result);
    }

    /** 优惠券过期清理(每天 3:30,避开业务高峰)。 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void expireCouponRecords() {
        var result = tenantTaskRunner.runForEachTenant("优惠券过期清理", SYSTEM_ACTOR_ID,
                tenantId -> {
                    int expired = marketingMaintenanceService.expireOutdatedCouponRecords();
                    if (expired > 0) {
                        log.info("租户 {} 过期优惠券清理 {} 条", tenantId, expired);
                    }
                });
        logIfFailed("优惠券过期清理", result);
    }

    /** 读字典里的整数配置;取不到或格式不对时用默认值,并把情况记下来。 */
    private int dictInt(String dictType, int defaultValue) {
        return dictIntAt(dictType, 0, defaultValue);
    }

    /**
     * 读字典里第 {@code index} 个条目的整数值。
     *
     * <p>一个字典类型下有多档配置时(after_sale_timeout 有三档)只能按顺序取 ——
     * 字典的条目按 {@code sort_order} 返回,所以这里的下标与 V4 种子数据的顺序是对应的。
     * 取不到、越界、或值不是整数时一律退回默认值:配置问题不该让任务整体卡住。
     */
    private int dictIntAt(String dictType, int index, int defaultValue) {
        try {
            List<DictItemView> items = dictService.items(dictType);
            if (items.size() <= index) {
                return defaultValue;
            }
            return Integer.parseInt(items.get(index).value().trim());
        } catch (NumberFormatException ex) {
            log.warn("字典 {} 第 {} 项不是整数,已使用默认值 {}", dictType, index, defaultValue);
            return defaultValue;
        }
    }

    private void logIfFailed(String taskName, TenantTaskRunner.RunResult result) {
        if (result.failedTenantIds() != null && !result.failedTenantIds().isEmpty()) {
            log.warn("任务「{}」部分租户执行失败: {}", taskName, result.failedTenantIds());
        }
    }
}
