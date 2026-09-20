package com.minimall.mall.service;

import com.minimall.mall.api.dto.ClientOrderView;
import com.minimall.mall.api.dto.CreateOrderRequest;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.MallCustomerAddress;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallCustomerAddressRepository;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallOrderItemRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.domain.repository.MallStockLogRepository;
import com.minimall.mall.domain.repository.MallWxPaymentRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.infra.auth.ClientPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商城下单与支付链路集成测试(商城设计文档 3.3、3.4、3.8)。
 *
 * <p>覆盖的是"钱与货"这条最关键的路径,而且刻意用真实数据库:
 * <ul>
 *   <li><b>库存的两个字段怎么变</b>:下单只动 {@code lockedStock},支付成功才同时扣 {@code stock} ——
 *       写反了就会超卖或永远卖不出货</li>
 *   <li><b>回调幂等</b>:微信会重复推送,重复处理会导致库存被扣两次</li>
 *   <li><b>取消要退券与释放库存</b>:漏掉退货的那一步,买家会白丢一张券</li>
 * </ul>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class MallOrderFlowIntegrationTest {

    /** 种子数据里的平台租户。 */
    private static final long TENANT_ID = 1L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PayService payService;

    @Autowired
    private MallCustomerRepository customerRepository;

    @Autowired
    private MallCustomerAddressRepository addressRepository;

    @Autowired
    private MallGoodsRepository goodsRepository;

    @Autowired
    private MallSkuRepository skuRepository;

    @Autowired
    private MallOrderRepository orderRepository;

    @Autowired
    private MallOrderItemRepository orderItemRepository;

    @Autowired
    private MallWxPaymentRepository paymentRepository;

    @Autowired
    private MallStockLogRepository stockLogRepository;

    /** 用于调整 {@code create_time} 这类实体不允许修改的列(模拟时间流逝)。 */
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Long customerId;
    private Long addressId;
    private Long goodsId;
    private Long skuId;

    @BeforeEach
    void setUp() {
        inTenant(() -> {
            MallCustomer customer = new MallCustomer();
            customer.setOpenid("it-openid-" + System.nanoTime());
            customer.setStatus(1);
            customer.setPoints(0);
            customer.setGrowthValue(0);
            customer.setRegisterTime(LocalDateTime.now());
            customerId = customerRepository.save(customer).getId();

            MallCustomerAddress address = new MallCustomerAddress();
            address.setCustomerId(customerId);
            address.setReceiverName("集成测试");
            address.setReceiverPhone("13800000000");
            address.setProvince("广东省");
            address.setCity("深圳市");
            address.setDistrict("南山区");
            address.setDetailAddress("测试路 1 号");
            address.setIsDefault(1);
            addressId = addressRepository.save(address).getId();

            MallGoods goods = new MallGoods();
            goods.setCategoryId(0L);
            goods.setGoodsName("集成测试商品");
            goods.setMainImage("https://example.com/it.png");
            goods.setSalePriceMin(new BigDecimal("50.00"));
            goods.setSalePriceMax(new BigDecimal("50.00"));
            goods.setTotalStock(10);
            goods.setSaleCount(0);
            goods.setStatus(1);
            goods.setSortOrder(0);
            goodsId = goodsRepository.save(goods).getId();

            MallSku sku = new MallSku();
            sku.setGoodsId(goodsId);
            sku.setSkuCode("IT-SKU-" + System.nanoTime());
            sku.setSkuName("默认规格");
            sku.setPrice(new BigDecimal("50.00"));
            sku.setStock(10);
            sku.setLockedStock(0);
            sku.setStatus(1);
            skuId = skuRepository.save(sku).getId();
            return null;
        });
        ClientContext.set(new ClientPrincipal(TENANT_ID, customerId));
        // 真实请求里租户上下文由 ClientAuthFilter 设置;测试直接调 service,得自己摆好
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        ClientContext.clear();
        inTenant(() -> {
            orderItemRepository.findAll().stream()
                    .filter(item -> item.getGoodsId().equals(goodsId))
                    .forEach(orderItemRepository::delete);
            orderRepository.findAll().stream()
                    .filter(order -> order.getCustomerId().equals(customerId))
                    .forEach(order -> {
                        paymentRepository.findFirstByOrderIdOrderByIdDesc(order.getId())
                                .ifPresent(paymentRepository::delete);
                        orderRepository.delete(order);
                    });
            stockLogRepository.findAll().stream()
                    .filter(log -> log.getSkuId().equals(skuId))
                    .forEach(stockLogRepository::delete);
            skuRepository.findById(skuId).ifPresent(skuRepository::delete);
            goodsRepository.findById(goodsId).ifPresent(goodsRepository::delete);
            addressRepository.findById(addressId).ifPresent(addressRepository::delete);
            customerRepository.findById(customerId).ifPresent(customerRepository::delete);
            return null;
        });
        TenantContext.clear();
    }

    @Test
    @DisplayName("用例1:下单只锁定库存,不动实际库存(可售库存随之减少)")
    void createOrderLocksStockOnly() {
        OrderCreateResponse response = orderService.create(
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, 2)), addressId, null, "尽快发货"));

        assertThat(response.orderNo()).hasSize(16);
        assertThat(response.goodsAmount()).isEqualByComparingTo("100.00");
        assertThat(response.payAmount()).isEqualByComparingTo("100.00");

        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            assertThat(sku.getStock()).as("下单不动实际库存").isEqualTo(10);
            assertThat(sku.getLockedStock()).as("下单锁定 2 件").isEqualTo(2);
            assertThat(sku.availableStock()).isEqualTo(8);

            MallOrder order = orderRepository.findById(response.orderId()).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(MallOrder.STATUS_PENDING_PAY);
            assertThat(order.getReceiverAddress()).isEqualTo("广东省深圳市南山区测试路 1 号");
            assertThat(orderItemRepository.findByOrderIdOrderByIdAsc(order.getId())).hasSize(1);
            return null;
        });
    }

    @Test
    @DisplayName("用例2:支付回调同时扣减实际库存与锁定库存,并累加销量")
    void payCallbackDeductsBothStockFields() {
        OrderCreateResponse response = orderService.create(
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, 3)), addressId, null, null));

        payService.handlePayCallback(response.orderNo(), "wx-txn-" + System.nanoTime(),
                response.payAmount(), true, "{\"mock\":true}");

        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            assertThat(sku.getStock()).as("实际库存扣减 3").isEqualTo(7);
            assertThat(sku.getLockedStock()).as("锁定库存同步释放").isEqualTo(0);
            assertThat(sku.availableStock()).isEqualTo(7);

            MallOrder order = orderRepository.findById(response.orderId()).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(MallOrder.STATUS_PENDING_SHIP);
            assertThat(order.getPayTime()).isNotNull();
            assertThat(goodsRepository.findById(goodsId).orElseThrow().getSaleCount())
                    .as("销量按购买数量累加").isEqualTo(3);
            return null;
        });
    }

    @Test
    @DisplayName("用例3:重复的支付回调不会重复扣库存(幂等)")
    void duplicateCallbackIsIdempotent() {
        OrderCreateResponse response = orderService.create(
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, 2)), addressId, null, null));
        String transactionId = "wx-txn-dup-" + System.nanoTime();

        payService.handlePayCallback(response.orderNo(), transactionId, response.payAmount(), true, "{}");
        payService.handlePayCallback(response.orderNo(), transactionId, response.payAmount(), true, "{}");

        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            assertThat(sku.getStock()).as("第二次回调不得再扣一次库存").isEqualTo(8);
            assertThat(goodsRepository.findById(goodsId).orElseThrow().getSaleCount()).isEqualTo(2);
            return null;
        });
    }

    @Test
    @DisplayName("用例4:支付回调金额与订单金额不一致时拒绝处理(防篡改)")
    void callbackRejectsAmountMismatch() {
        OrderCreateResponse response = orderService.create(
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, 1)), addressId, null, null));

        assertThatThrownBy(() -> payService.handlePayCallback(response.orderNo(),
                "wx-txn-bad-" + System.nanoTime(), new BigDecimal("0.01"), true, "{}"))
                .hasMessageContaining("金额不一致");

        inTenant(() -> {
            assertThat(skuRepository.findById(skuId).orElseThrow().getStock())
                    .as("金额不符时库存不能被扣").isEqualTo(10);
            return null;
        });
    }

    @Test
    @DisplayName("用例5:买家取消订单释放锁定库存,订单进入已关闭")
    void cancelReleasesLockedStock() {
        OrderCreateResponse response = orderService.create(
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, 4)), addressId, null, null));

        orderService.cancel(response.orderId());

        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            assertThat(sku.getLockedStock()).isZero();
            assertThat(sku.availableStock()).as("取消后库存回到可售").isEqualTo(10);

            MallOrder order = orderRepository.findById(response.orderId()).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(MallOrder.STATUS_CANCELLED);
            assertThat(order.getCloseReason()).isEqualTo(2);
            return null;
        });
    }

    @Test
    @DisplayName("用例6:超时未支付由系统关闭并释放库存(3.4)")
    void timeoutClosesAndReleases() {
        OrderCreateResponse response = orderService.create(
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, 2)), addressId, null, null));

        inTenant(() -> {
            // 把下单时间往前推,模拟已超过支付超时(不依赖等待真实的 15 分钟)。
            // 必须用原生 SQL:create_time 是 @CreatedDate + updatable = false,
            // 通过实体改它不会被持久化(那正是"创建时间不可篡改"的保护)
            jdbcTemplate.update("update mall_order set create_time = ? where id = ?",
                    LocalDateTime.now().minusMinutes(30), response.orderId());
            return null;
        });

        int closed = inTenant(() -> orderService.closeTimeoutOrders(LocalDateTime.now().minusMinutes(15)));

        assertThat(closed).isGreaterThanOrEqualTo(1);
        inTenant(() -> {
            MallOrder order = orderRepository.findById(response.orderId()).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(MallOrder.STATUS_CANCELLED);
            assertThat(order.getCloseReason()).isEqualTo(1);
            assertThat(skuRepository.findById(skuId).orElseThrow().getLockedStock()).isZero();
            return null;
        });
    }

    @Test
    @DisplayName("用例7:库存不足时下单失败,且不会留下订单(整笔回滚)")
    void insufficientStockRollsBackEverything() {
        assertThatThrownBy(() -> orderService.create(
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, 11)), addressId, null, null)))
                .hasMessageContaining("库存不足");

        inTenant(() -> {
            assertThat(orderRepository.findAll().stream()
                    .filter(order -> order.getCustomerId().equals(customerId)).toList())
                    .as("失败的订单不能留在库里")
                    .isEmpty();
            assertThat(skuRepository.findById(skuId).orElseThrow().getLockedStock()).isZero();
            return null;
        });
    }

    @Test
    @DisplayName("用例8:订单查询只返回自己的订单(跨客户不可见)")
    void ordersAreScopedToCustomer() {
        OrderCreateResponse response = orderService.create(
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, 1)), addressId, null, null));

        // 换一个客户身份,应当看不到上面那笔订单
        Long otherCustomerId = inTenant(() -> {
            MallCustomer other = new MallCustomer();
            other.setOpenid("it-other-" + System.nanoTime());
            other.setStatus(1);
            other.setPoints(0);
            other.setGrowthValue(0);
            other.setRegisterTime(LocalDateTime.now());
            return customerRepository.save(other).getId();
        });
        ClientContext.set(new ClientPrincipal(TENANT_ID, otherCustomerId));
        assertThatThrownBy(() -> orderService.detail(response.orderId()))
                .hasMessageContaining("订单不存在");

        ClientContext.set(new ClientPrincipal(TENANT_ID, customerId));
        ClientOrderView view = orderService.detail(response.orderId());
        assertThat(view.orderNo()).isEqualTo(response.orderNo());

        // 清理另一个客户
        inTenant(() -> {
            customerRepository.findById(otherCustomerId).ifPresent(customerRepository::delete);
            return null;
        });
    }

    private <T> T inTenant(java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, false, () -> {
            AuditContext.bind(new AuditContext(TENANT_ID, customerId, "127.0.0.1", "mall-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }
}
