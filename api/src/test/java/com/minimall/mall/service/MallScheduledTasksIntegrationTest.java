package com.minimall.mall.service;

import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderStatusLog;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallOrderStatusLogRepository;
import com.minimall.sys.api.dto.DictDataSaveRequest;
import com.minimall.sys.api.dto.DictDataView;
import com.minimall.sys.service.DictService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 商城定时任务(商城设计文档第 4 节)。
 *
 * <p>为什么值得单独测:这些任务没有用户点按钮,出问题时**没有任何人会发现** ——
 * 订单不会自动关闭(库存被永久锁死)、收货不会自动确认(货款永远不到账)。
 * 而它们的触发条件是"时间过去了",只能靠把 {@code create_time}/{@code ship_time} 改到过去来构造,
 * 这正是夹具里那个 {@code JdbcTemplate} 的用途。
 *
 * <p>任务内部走 {@code TenantTaskRunner}:逐租户独立事务。它在**事务内调用会被直接拒绝**,
 * 所以用例必须在事务外调用(测试方法本身不加 @Transactional 就是这个原因)。
 */
class MallScheduledTasksIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private MallScheduledTasks scheduledTasks;
    @Autowired
    private MallOrderStatusLogRepository statusLogRepository;
    @Autowired
    private DictService dictService;

    private void backdateOrder(Long orderId, String column, LocalDateTime value) {
        inTenant(() -> {
            jdbcTemplate.update("UPDATE mall_order SET " + column + " = ? WHERE id = ?", value, orderId);
            return null;
        });
    }

    private List<MallOrderStatusLog> logsOf(Long orderId) {
        return inTenant(() -> statusLogRepository.findAll().stream()
                .filter(log -> orderId.equals(log.getOrderId()))
                .toList());
    }

    @Test
    @DisplayName("超时未支付订单自动关闭:回补锁定库存,并把流水记为系统操作")
    void closeTimeoutOrdersReleasesLockedStock() {
        OrderCreateResponse order = createOrder(customerId, addressId, 3);
        // 字典默认 15 分钟超时:把下单时间推到 30 分钟前
        backdateOrder(order.orderId(), "create_time", LocalDateTime.now().minusMinutes(30));

        scheduledTasks.closeTimeoutOrders();

        inTenant(() -> {
            MallOrder reloaded = orderRepository.findById(order.orderId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(MallOrder.STATUS_CANCELLED);

            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            assertThat(sku.getLockedStock()).as("锁定的库存必须回补,否则库存被永久锁死").isZero();
            assertThat(sku.getStock()).as("未支付订单本来就没扣实际库存").isEqualTo(10);
            return null;
        });

        List<MallOrderStatusLog> logs = logsOf(order.orderId());
        assertThat(logs).as("状态流转要留痕").isNotEmpty();
        assertThat(logs.get(logs.size() - 1).getOperatorType())
                .as("系统关单的 operator_type 必须能与人工操作区分开")
                .isEqualTo(MallOrderStatusLog.OPERATOR_SYSTEM);
        assertThat(logs.get(logs.size() - 1).getToStatus()).isEqualTo(MallOrder.STATUS_CANCELLED);
    }

    @Test
    @DisplayName("未超时的订单不动(任务不能把刚下的单关掉)")
    void closeTimeoutOrdersLeavesFreshOrdersAlone() {
        OrderCreateResponse order = createOrder(customerId, addressId, 1);

        scheduledTasks.closeTimeoutOrders();

        inTenant(() -> {
            assertThat(orderRepository.findById(order.orderId()).orElseThrow().getStatus())
                    .isEqualTo(MallOrder.STATUS_PENDING_PAY);
            return null;
        });
    }

    @Test
    @DisplayName("待收货订单超期自动确认收货")
    void autoReceiveFinishesOverdueOrder() {
        OrderCreateResponse order = createOrder(customerId, addressId, 1);
        forceOrderStatus(order.orderId(), MallOrder.STATUS_PENDING_RECEIVE);
        backdateOrder(order.orderId(), "ship_time", LocalDateTime.now().minusDays(20));

        scheduledTasks.autoReceiveOrders();

        inTenant(() -> {
            MallOrder reloaded = orderRepository.findById(order.orderId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(MallOrder.STATUS_FINISHED);
            assertThat(reloaded.getFinishTime()).isNotNull();
            return null;
        });
        assertThat(logsOf(order.orderId())).isNotEmpty();
    }

    @Test
    @DisplayName("未超期的待收货订单不动")
    void autoReceiveLeavesFreshOrderAlone() {
        OrderCreateResponse order = createOrder(customerId, addressId, 1);
        forceOrderStatus(order.orderId(), MallOrder.STATUS_PENDING_RECEIVE);
        backdateOrder(order.orderId(), "ship_time", LocalDateTime.now());

        scheduledTasks.autoReceiveOrders();

        inTenant(() -> {
            assertThat(orderRepository.findById(order.orderId()).orElseThrow().getStatus())
                    .isEqualTo(MallOrder.STATUS_PENDING_RECEIVE);
            return null;
        });
    }

    @Test
    @DisplayName("字典里的超时值写错时退回默认值,任务不能整体失效")
    void brokenDictValueFallsBackToDefault() {
        List<DictDataView> items = inTenant(() -> dictService.listData("order_pay_timeout_minutes"));
        assertThat(items).as("V4 种子数据里应当配了这个字典").isNotEmpty();
        DictDataView target = items.get(0);
        String originalValue = target.dictValue();

        try {
            inTenant(() -> {
                dictService.updateData(target.id(), new DictDataSaveRequest(
                        target.dictType(), target.dictLabel(), "不是数字", target.sortOrder()));
                return null;
            });

            OrderCreateResponse order = createOrder(customerId, addressId, 1);
            backdateOrder(order.orderId(), "create_time", LocalDateTime.now().minusMinutes(30));

            assertThatCode(() -> scheduledTasks.closeTimeoutOrders())
                    .as("配置写错不该让整个任务抛异常(那样所有租户的订单都不会被关闭)")
                    .doesNotThrowAnyException();

            inTenant(() -> {
                assertThat(orderRepository.findById(order.orderId()).orElseThrow().getStatus())
                        .as("退回默认 15 分钟后仍然应当关掉 30 分钟前的单")
                        .isEqualTo(MallOrder.STATUS_CANCELLED);
                return null;
            });
        } finally {
            inTenant(() -> {
                dictService.updateData(target.id(), new DictDataSaveRequest(
                        target.dictType(), target.dictLabel(), originalValue, target.sortOrder()));
                return null;
            });
        }
    }

    @Test
    @DisplayName("售后超时任务:没有超期单时是空跑,不抛异常(它一次跑三条规则)")
    void afterSaleTimeoutTaskIsNoopWhenNothingOverdue() {
        assertThatCode(() -> scheduledTasks.handleAfterSaleTimeout()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("优惠券过期清理:同样可空跑")
    void expireCouponRecordsIsNoopWhenNothingOverdue() {
        assertThatCode(() -> scheduledTasks.expireCouponRecords()).doesNotThrowAnyException();
    }
}
