package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.mall.api.dto.CartAddRequest;
import com.minimall.mall.api.dto.CartUpdateRequest;
import com.minimall.mall.api.dto.ClientOrderView;
import com.minimall.mall.api.dto.CreateOrderRequest;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderItem;
import com.minimall.mall.domain.MallOrderStatusLog;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.MallWxPayment;
import com.minimall.mall.domain.repository.MallOrderStatusLogRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.infra.auth.ClientPrincipal;
import com.minimall.infra.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单服务的分支与边界(商城设计文档 3.3、3.4)。
 *
 * <p>为什么单独一个类:{@code OrderServiceImpl} 没有任何"整方法没被执行"的方法 ——
 * 主流程被 {@code MallOrderFlowIntegrationTest} 覆盖得很好,缺的全是**方法内部的错误分支**。
 * 这类缺口靠"再造一个主流程用例"永远补不上,必须逐个构造触发条件:
 * 库存不足、商品下架、地址不是自己的、购物车里没勾选、租户上下文丢了……
 *
 * <p>下单相关的两条并发保护(条件更新受影响行数为 0)在单线程测试里无法稳定触发,
 * 这里没有硬造 —— 它们靠"先改实体再做批量更新"的顺序约定 + 生产环境的条件更新兜住,
 * 属于测试覆盖不到的残余风险,已记在文档的遗留项里。
 */
class OrderServiceBranchIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private PayService payService;
    @Autowired
    private OrderAdminService orderAdminService;
    @Autowired
    private CartService cartService;
    @Autowired
    private MallOrderStatusLogRepository statusLogRepository;

    private CreateOrderRequest requestOf(Long sku, int quantity) {
        return new CreateOrderRequest(List.of(new CreateOrderRequest.Item(sku, quantity)), addressId, null, "测试留言");
    }

    /** 把订单推进到"待收货"(支付 + 发货)。备注走 requestOf,便于断言留言透传。 */
    private OrderCreateResponse deliverableOrder() {
        OrderCreateResponse order = asClient(customerId, () -> orderService.create(requestOf(skuId, 1)));
        inTenant(() -> {
            payService.handlePayCallback(order.orderNo(), "wx-txn-" + System.nanoTime(),
                    order.payAmount(), true, "{\"mock\":true}");
            return null;
        });
        inTenant(() -> {
            orderAdminService.ship(order.orderId(), "顺丰", "SF-BRANCH");
            return null;
        });
        return order;
    }

    // ---------------------------------------------------------------- 确认收货

    @Test
    @DisplayName("确认收货:订单转已完成并记录收货/完成时间")
    void confirmReceiveFinishesOrder() {
        OrderCreateResponse order = deliverableOrder();

        asClientRun(customerId, () -> orderService.confirmReceive(order.orderId()));

        inTenant(() -> {
            MallOrder reloaded = orderRepository.findById(order.orderId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(MallOrder.STATUS_FINISHED);
            assertThat(reloaded.getReceiveTime()).isNotNull();
            assertThat(reloaded.getFinishTime()).isNotNull();
            return null;
        });
        List<MallOrderStatusLog> logs = inTenant(() -> statusLogRepository.findAll().stream()
                .filter(logRow -> order.orderId().equals(logRow.getOrderId()))
                .toList());
        assertThat(logs).anyMatch(logRow -> logRow.getToStatus() == MallOrder.STATUS_FINISHED
                && logRow.getOperatorType() == MallOrderStatusLog.OPERATOR_BUYER);
    }

    @Test
    @DisplayName("确认收货:只有待收货的订单可以确认")
    void confirmReceiveRejectsWrongStatus() {
        OrderCreateResponse order = createOrder(customerId, addressId, 1);

        assertThatThrownBy(() -> asClientRun(customerId, () -> orderService.confirmReceive(order.orderId())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有待收货的订单可以确认收货");
    }

    @Test
    @DisplayName("确认收货:别人的订单按不存在处理")
    void confirmReceiveRejectsForeignOrder() {
        Long othersAddress = inTenant(() -> newAddress(otherCustomerId, "别人"));
        OrderCreateResponse othersOrder = createOrder(otherCustomerId, othersAddress, 1);

        assertThatThrownBy(() -> asClientRun(customerId, () -> orderService.confirmReceive(othersOrder.orderId())))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------------------------------------------------------------- 取消

    @Test
    @DisplayName("买家取消:只有待支付的订单可以取消")
    void cancelRejectsNonPendingPayOrder() {
        OrderCreateResponse order = deliverableOrder();

        assertThatThrownBy(() -> asClientRun(customerId, () -> orderService.cancel(order.orderId())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有待支付的订单可以取消");
    }

    @Test
    @DisplayName("买家取消:不存在的订单给出 NOT_FOUND")
    void cancelRejectsUnknownOrder() {
        assertThatThrownBy(() -> asClientRun(customerId, () -> orderService.cancel(999999L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("重复释放库存只记日志,不打断流程(已释放过一次时受影响行数为 0)")
    void repeatedReleaseIsLoggedNotThrown() {
        OrderCreateResponse order = createOrder(customerId, addressId, 2);
        asClientRun(customerId, () -> orderService.cancel(order.orderId()));

        // 把状态改回待支付再取消一次:锁定库存已经释放过,第二次释放受影响行数为 0。
        // 这条路径在"人工干预/重复关闭"时会出现,必须只是记日志,不能抛异常把批处理打断
        inTenant(() -> {
            jdbcTemplate.update("UPDATE mall_order SET status = ?, cancel_time = NULL WHERE id = ?",
                    MallOrder.STATUS_PENDING_PAY, order.orderId());
            return null;
        });

        assertThatCode(() -> asClientRun(customerId, () -> orderService.cancel(order.orderId())))
                .doesNotThrowAnyException();
        inTenant(() -> {
            assertThat(orderRepository.findById(order.orderId()).orElseThrow().getStatus())
                    .as("状态仍然要被置为已取消")
                    .isEqualTo(MallOrder.STATUS_CANCELLED);
            return null;
        });
    }

    // ---------------------------------------------------------------- 查询

    @Test
    @DisplayName("订单列表:按状态过滤,并按分页返回总数")
    void listFiltersByStatus() {
        OrderCreateResponse pendingPay = createOrder(customerId, addressId, 1);
        OrderCreateResponse finished = deliverableOrder();
        asClientRun(customerId, () -> orderService.confirmReceive(finished.orderId()));

        PageResult<ClientOrderView> all = asClient(customerId, () -> orderService.list(null, 1, 50));
        assertThat(all.total()).isGreaterThanOrEqualTo(2);

        PageResult<ClientOrderView> pendingOnly =
                asClient(customerId, () -> orderService.list(MallOrder.STATUS_PENDING_PAY, 1, 50));
        assertThat(pendingOnly.list()).extracting(ClientOrderView::id)
                .contains(pendingPay.orderId())
                .doesNotContain(finished.orderId());

        PageResult<ClientOrderView> firstPage = asClient(customerId, () -> orderService.list(null, 1, 1));
        assertThat(firstPage.list()).as("每页 1 条时只返回 1 条").hasSize(1);
        assertThat(firstPage.total()).isGreaterThanOrEqualTo(2);

        ClientOrderView detail = asClient(customerId, () -> orderService.detail(finished.orderId()));
        assertThat(detail.items()).hasSize(1);
        assertThat(detail.receiveTime()).isNotNull();
        assertThat(detail.remark()).isEqualTo("测试留言");
    }

    @Test
    @DisplayName("订单详情:别人的订单按不存在处理")
    void detailRejectsForeignOrder() {
        Long othersAddress = inTenant(() -> newAddress(otherCustomerId, "别人"));
        OrderCreateResponse othersOrder = createOrder(otherCustomerId, othersAddress, 1);

        assertThatThrownBy(() -> asClient(customerId, () -> orderService.detail(othersOrder.orderId())))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------------------------------------------------------------- 下单的边界

    @Test
    @DisplayName("下单:没有租户上下文时直接 UNAUTHORIZED(不去猜一个租户)")
    void createWithoutTenantIsUnauthorized() {
        ClientContext.set(new ClientPrincipal(TENANT_ID, customerId));
        try {
            assertThatThrownBy(() -> orderService.create(requestOf(skuId, 1)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ErrorCode.UNAUTHORIZED));
        } finally {
            ClientContext.clear();
        }
    }

    @Test
    @DisplayName("下单:购物车里一条都没勾选时,不存在可结算的商品")
    void createRejectsEmptySettlementLines() {
        Long cartId = asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 1)));
        asClientRun(customerId, () -> cartService.update(cartId, new CartUpdateRequest(null, 0)));

        assertThatThrownBy(() -> asClient(customerId, () -> orderService.create(
                new CreateOrderRequest(Collections.emptyList(), addressId, null, null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可结算的商品");
    }

    @Test
    @DisplayName("下单:购物车下单只结算已勾选的条目,并在下单后清掉它们")
    void createFromCartSettlesSelectedAndClearsThem() {
        Long cartId = asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 3)));

        OrderCreateResponse order = asClient(customerId, () -> orderService.create(
                new CreateOrderRequest(Collections.emptyList(), addressId, null, null)));

        inTenant(() -> {
            List<MallOrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(order.orderId());
            assertThat(items).hasSize(1);
            assertThat(items.get(0).getQuantity()).as("数量取自购物车").isEqualTo(3);
            assertThat(cartRepository.findById(cartId))
                    .as("已结算的购物车条目要被清掉,否则下次结算会重复买")
                    .isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("下单:地址必须是自己的")
    void createRejectsForeignAddress() {
        Long othersAddress = inTenant(() -> newAddress(otherCustomerId, "别人"));

        assertThatThrownBy(() -> asClient(customerId, () -> orderService.create(
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, 1)), othersAddress, null, null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("收货地址不存在");
    }

    @Test
    @DisplayName("下单:SKU 不存在 / 商品已下架 / 规格已停售 / 库存不足,都要给出可读的错误")
    void createRejectsUnsellableLines() {
        assertThatThrownBy(() -> asClient(customerId, () -> orderService.create(requestOf(999999L, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("商品规格不存在");

        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            sku.setGoodsId(999999L);
            skuRepository.save(sku);
            return null;
        });
        assertThatThrownBy(() -> asClient(customerId, () -> orderService.create(requestOf(skuId, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("商品不存在");

        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            sku.setGoodsId(goodsId);
            sku.setStatus(0);
            skuRepository.save(sku);
            return null;
        });
        assertThatThrownBy(() -> asClient(customerId, () -> orderService.create(requestOf(skuId, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已下架或停售");

        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            sku.setStatus(1);
            skuRepository.save(sku);
            MallGoods goods = goodsRepository.findById(goodsId).orElseThrow();
            goods.setStatus(0);
            goodsRepository.save(goods);
            return null;
        });
        assertThatThrownBy(() -> asClient(customerId, () -> orderService.create(requestOf(skuId, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已下架或停售");

        inTenant(() -> {
            MallGoods goods = goodsRepository.findById(goodsId).orElseThrow();
            goods.setStatus(1);
            goodsRepository.save(goods);
            return null;
        });
        assertThatThrownBy(() -> asClient(customerId, () -> orderService.create(requestOf(skuId, 11))))
                .as("夹具库存 10")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");
    }

    @Test
    @DisplayName("下单:SKU 有自己的图就用它,否则退回商品主图")
    void createPrefersSkuImageOverGoodsMainImage() {
        OrderCreateResponse withoutSkuImage = createOrder(customerId, addressId, 1);
        inTenant(() -> {
            assertThat(orderItemRepository.findByOrderIdOrderByIdAsc(withoutSkuImage.orderId()).get(0).getGoodsImage())
                    .as("SKU 没配图时用商品主图")
                    .isEqualTo("https://example.com/it.png");
            return null;
        });

        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            sku.setSkuImage("https://example.com/sku.png");
            skuRepository.save(sku);
            return null;
        });
        OrderCreateResponse withSkuImage = createOrder(customerId, addressId, 1);
        inTenant(() -> {
            assertThat(orderItemRepository.findByOrderIdOrderByIdAsc(withSkuImage.orderId()).get(0).getGoodsImage())
                    .as("SKU 配了图就优先用自己的")
                    .isEqualTo("https://example.com/sku.png");
            return null;
        });
    }

    @Test
    @DisplayName("下单:同时写下支付流水与状态流水(支付回调要靠前者找到这笔订单)")
    void createWritesPaymentAndStatusLog() {
        OrderCreateResponse order = createOrder(customerId, addressId, 2);

        inTenant(() -> {
            MallWxPayment payment = paymentRepository.findFirstByOrderIdOrderByIdDesc(order.orderId()).orElseThrow();
            assertThat(payment.getPayStatus()).isEqualTo(MallWxPayment.PAY_STATUS_PENDING);
            assertThat(payment.getOutTradeNo()).isEqualTo(order.orderNo());
            assertThat(payment.getPayAmount()).isEqualByComparingTo(order.payAmount());

            List<MallOrderStatusLog> logs = statusLogRepository.findAll().stream()
                    .filter(logRow -> order.orderId().equals(logRow.getOrderId()))
                    .toList();
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).getFromStatus()).as("新建订单没有前置状态").isNull();
            assertThat(logs.get(0).getToStatus()).isEqualTo(MallOrder.STATUS_PENDING_PAY);
            return null;
        });
    }

    @Test
    @DisplayName("下单:商品没挂运费模板时包邮")
    void createChargesNoFreightWithoutTemplate() {
        OrderCreateResponse order = createOrder(customerId, addressId, 1);

        assertThat(order.freightAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("下单:金额规整为两位小数")
    void createRoundsAmountsToTwoDecimals() {
        OrderCreateResponse order = asClient(customerId, () -> orderService.create(requestOf(skuId, 3)));

        assertThat(order.goodsAmount()).isEqualByComparingTo("150.00");
        assertThat(order.payAmount()).isEqualByComparingTo("150.00");
        inTenant(() -> {
            MallOrder saved = orderRepository.findById(order.orderId()).orElseThrow();
            assertThat(saved.getGoodsAmount().scale()).as("库里不该出现 150.0000000001 这种金额").isLessThanOrEqualTo(2);
            return null;
        });
    }

    @Test
    @DisplayName("超时关闭任务:按创建时间筛出未支付订单,已支付的不受影响")
    void closeTimeoutOrdersOnlyTouchesPendingPay() {
        OrderCreateResponse pending = createOrder(customerId, addressId, 1);
        OrderCreateResponse paid = deliverableOrder();
        inTenant(() -> {
            jdbcTemplate.update("UPDATE mall_order SET create_time = ? WHERE id IN (?, ?)",
                    java.time.LocalDateTime.now().minusMinutes(30), pending.orderId(), paid.orderId());
            return null;
        });

        int closed = inTenant(() -> orderService.closeTimeoutOrders(java.time.LocalDateTime.now()));

        assertThat(closed).isGreaterThanOrEqualTo(1);
        inTenant(() -> {
            assertThat(orderRepository.findById(pending.orderId()).orElseThrow().getStatus())
                    .isEqualTo(MallOrder.STATUS_CANCELLED);
            assertThat(orderRepository.findById(paid.orderId()).orElseThrow().getStatus())
                    .as("已支付的订单不能被超时任务碰")
                    .isEqualTo(MallOrder.STATUS_PENDING_RECEIVE);
            return null;
        });
    }
}
