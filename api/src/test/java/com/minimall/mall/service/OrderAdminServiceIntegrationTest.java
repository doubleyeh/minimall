package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.mall.api.dto.AdminOrderView;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderStatusLog;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.MallWxRefund;
import com.minimall.mall.domain.repository.MallOrderStatusLogRepository;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商家管理端订单(商城设计文档 3.4)。
 *
 * <p>这一层里最要紧的是**商家取消**那一条:回补库存与创建退款记录必须在同一事务内。
 * 只回补不退款是吞钱,只退款不回补是白送一件货 —— 两者都是"能对上账、但账是错的",
 * 靠人工对账要很久才能发现。
 */
class OrderAdminServiceIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private OrderAdminService orderAdminService;
    @Autowired
    private PayService payService;
    @Autowired
    private MallOrderStatusLogRepository statusLogRepository;
    @Autowired
    private MallWxRefundRepository refundRepository;

    /** 造一个"已支付待发货"的订单:cancel 与 ship 的前置状态。 */
    private OrderCreateResponse paidOrder(int quantity) {
        OrderCreateResponse order = createOrder(customerId, addressId, quantity);
        inTenant(() -> {
            payService.handlePayCallback(order.orderNo(), "wx-txn-" + System.nanoTime(),
                    order.payAmount(), true, "{\"mock\":true}");
            return null;
        });
        assertThat(inTenant(() -> orderRepository.findById(order.orderId()).orElseThrow().getStatus()))
                .as("前置条件:订单必须真的到了待发货")
                .isEqualTo(MallOrder.STATUS_PENDING_SHIP);
        return order;
    }

    private List<MallOrderStatusLog> logsOf(Long orderId) {
        return inTenant(() -> statusLogRepository.findAll().stream()
                .filter(log -> orderId.equals(log.getOrderId()))
                .toList());
    }

    @Test
    @DisplayName("发货:状态转待收货并记物流,流水记商家操作")
    void shipMovesToPendingReceive() {
        OrderCreateResponse order = paidOrder(1);

        inTenant(() -> {
            orderAdminService.ship(order.orderId(), "顺丰", "SF123456");
            return null;
        });

        inTenant(() -> {
            MallOrder reloaded = orderRepository.findById(order.orderId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(MallOrder.STATUS_PENDING_RECEIVE);
            assertThat(reloaded.getLogisticsCompany()).isEqualTo("顺丰");
            assertThat(reloaded.getLogisticsNo()).isEqualTo("SF123456");
            assertThat(reloaded.getShipTime()).isNotNull();
            return null;
        });

        List<MallOrderStatusLog> logs = logsOf(order.orderId());
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(logs.size() - 1).getOperatorType()).isEqualTo(MallOrderStatusLog.OPERATOR_MERCHANT);
        assertThat(logs.get(logs.size() - 1).getToStatus()).isEqualTo(MallOrder.STATUS_PENDING_RECEIVE);
    }

    @Test
    @DisplayName("发货:物流信息必填")
    void shipRequiresLogistics() {
        OrderCreateResponse order = paidOrder(1);

        assertThatThrownBy(() -> inTenant(() -> {
            orderAdminService.ship(order.orderId(), "  ", null);
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请填写物流公司与物流单号");
    }

    @Test
    @DisplayName("发货:只有待发货的订单可以发货")
    void shipRejectsWrongStatus() {
        OrderCreateResponse order = createOrder(customerId, addressId, 1);

        assertThatThrownBy(() -> inTenant(() -> {
            orderAdminService.ship(order.orderId(), "顺丰", "SF1");
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有待发货的订单可以发货");
    }

    @Test
    @DisplayName("商家取消:库存回补 + 退款记录 + 流水,三件事一起发生")
    void cancelRestoresStockAndCreatesRefund() {
        OrderCreateResponse order = paidOrder(2);
        inTenant(() -> {
            assertThat(skuRepository.findById(skuId).orElseThrow().getStock())
                    .as("支付后实际库存已扣到 8")
                    .isEqualTo(8);
            return null;
        });

        inTenant(() -> {
            orderAdminService.cancel(order.orderId(), "缺货");
            return null;
        });

        inTenant(() -> {
            MallOrder reloaded = orderRepository.findById(order.orderId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(MallOrder.STATUS_CANCELLED);
            assertThat(reloaded.getCloseReason()).as("关闭原因要记下来,否则对账时看不出谁关的").isEqualTo(3);
            assertThat(reloaded.getCancelTime()).isNotNull();

            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            assertThat(sku.getStock()).as("已支付的库存是实扣的,取消必须放回去").isEqualTo(10);
            assertThat(sku.getLockedStock()).isZero();
            return null;
        });

        List<MallWxRefund> refunds = inTenant(() -> refundRepository.findAll().stream()
                .filter(refund -> order.orderId().equals(refund.getOrderId()))
                .toList());
        assertThat(refunds).as("只回补库存不退款就是吞钱").hasSize(1);
        assertThat(refunds.get(0).getRefundAmount()).isEqualByComparingTo(order.payAmount());
        assertThat(refunds.get(0).getOutRefundNo()).isNotBlank();
        assertThat(refunds.get(0).getRefundStatus())
                .as("mock 渠道会同步返回退款单号,状态应直接置为成功")
                .isEqualTo(MallWxRefund.REFUND_STATUS_SUCCESS);

        assertThat(logsOf(order.orderId())).isNotEmpty();
    }

    @Test
    @DisplayName("商家取消:未支付订单不能走这条路(它该超时自动关闭,不该产生退款)")
    void cancelRejectsUnpaidOrder() {
        OrderCreateResponse order = createOrder(customerId, addressId, 1);

        assertThatThrownBy(() -> inTenant(() -> {
            orderAdminService.cancel(order.orderId(), "不想卖了");
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有待发货的订单可以由商家取消");
    }

    @Test
    @DisplayName("商家取消:订单不存在")
    void cancelUnknownOrder() {
        assertThatThrownBy(() -> inTenant(() -> {
            orderAdminService.cancel(999999L, "x");
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("列表:可按订单号模糊与状态过滤,且带分页总数")
    void pageFilters() {
        OrderCreateResponse paid = paidOrder(1);
        OrderCreateResponse unpaid = createOrder(customerId, addressId, 1);

        PageResult<AdminOrderView> all = inTenant(() -> orderAdminService.page(null, null, 1, 50));
        assertThat(all.total()).isGreaterThanOrEqualTo(2);

        PageResult<AdminOrderView> onlyPaid =
                inTenant(() -> orderAdminService.page(null, MallOrder.STATUS_PENDING_SHIP, 1, 50));
        assertThat(onlyPaid.list()).extracting(AdminOrderView::id)
                .contains(paid.orderId())
                .doesNotContain(unpaid.orderId());

        PageResult<AdminOrderView> byOrderNo =
                inTenant(() -> orderAdminService.page(paid.orderNo(), null, 1, 50));
        assertThat(byOrderNo.list()).extracting(AdminOrderView::orderNo).containsExactly(paid.orderNo());

        PageResult<AdminOrderView> notFound = inTenant(() -> orderAdminService.page("不存在的订单号", null, 1, 50));
        assertThat(notFound.list()).isEmpty();
        assertThat(notFound.total()).isZero();
    }

    @Test
    @DisplayName("详情:带上订单明细")
    void detailIncludesItems() {
        OrderCreateResponse order = paidOrder(2);

        AdminOrderView view = inTenant(() -> orderAdminService.detail(order.orderId()));

        assertThat(view.id()).isEqualTo(order.orderId());
        assertThat(view.customerId()).isEqualTo(customerId);
        assertThat(view.items()).as("管理端详情要看得到买了什么").hasSize(1);
        assertThat(view.items().get(0).quantity()).isEqualTo(2);
        assertThat(view.items().get(0).goodsName()).isEqualTo("集成测试商品");
    }
}
