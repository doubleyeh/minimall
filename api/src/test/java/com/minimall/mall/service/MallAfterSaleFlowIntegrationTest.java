package com.minimall.mall.service;

import com.minimall.mall.api.dto.AfterSaleApplyRequest;
import com.minimall.mall.api.dto.CreateOrderRequest;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.common.BusinessException;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallAfterSale;
import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.MallCustomerAddress;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderItem;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallAfterSaleRepository;
import com.minimall.mall.domain.repository.MallCustomerAddressRepository;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallOrderItemRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.domain.repository.MallStockLogRepository;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 售后链路集成测试(商城设计文档 3.9)。
 *
 * <p>售后最容易出错的地方不是状态本身,而是**终态的下游动作**:
 * 退款有没有真的发起、库存有没有回补、订单状态有没有跟着回退。
 * 10 个状态的流转如果每条都各写一份下游动作,一定会漏掉其中一条 ——
 * 这个测试就是为了证明它们确实都走到了同一套终点逻辑。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class MallAfterSaleFlowIntegrationTest {

    private static final long TENANT_ID = 1L;
    /** 商家/超管操作人(审计快照里的 userId)。 */
    private static final long STAFF_ID = 1L;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PayService payService;

    @Autowired
    private AfterSaleService afterSaleService;

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
    private MallAfterSaleRepository afterSaleRepository;

    @Autowired
    private MallWxRefundRepository refundRepository;

    @Autowired
    private MallStockLogRepository stockLogRepository;

    private Long customerId;
    private Long addressId;
    private Long goodsId;
    private Long skuId;

    @BeforeEach
    void setUp() {
        inTenant(() -> {
            MallCustomer customer = new MallCustomer();
            customer.setOpenid("as-openid-" + System.nanoTime());
            customer.setStatus(1);
            customer.setPoints(0);
            customer.setGrowthValue(0);
            customer.setRegisterTime(LocalDateTime.now());
            customerId = customerRepository.save(customer).getId();

            MallCustomerAddress address = new MallCustomerAddress();
            address.setCustomerId(customerId);
            address.setReceiverName("售后测试");
            address.setReceiverPhone("13800000001");
            address.setProvince("广东省");
            address.setCity("深圳市");
            address.setDistrict("南山区");
            address.setDetailAddress("售后路 1 号");
            address.setIsDefault(1);
            addressId = addressRepository.save(address).getId();

            MallGoods goods = new MallGoods();
            goods.setCategoryId(0L);
            goods.setGoodsName("售后测试商品");
            goods.setMainImage("https://example.com/as.png");
            goods.setSalePriceMin(new BigDecimal("100.00"));
            goods.setSalePriceMax(new BigDecimal("100.00"));
            goods.setTotalStock(10);
            goods.setSaleCount(0);
            goods.setStatus(1);
            goods.setSortOrder(0);
            goodsId = goodsRepository.save(goods).getId();

            MallSku sku = new MallSku();
            sku.setGoodsId(goodsId);
            sku.setSkuCode("AS-SKU-" + System.nanoTime());
            sku.setSkuName("默认规格");
            sku.setPrice(new BigDecimal("100.00"));
            sku.setStock(10);
            sku.setLockedStock(0);
            sku.setStatus(1);
            skuId = skuRepository.save(sku).getId();
            return null;
        });
        asBuyer();
    }

    @AfterEach
    void tearDown() {
        ClientContext.clear();
        inTenant(() -> {
            List<Long> orderIds = orderRepository.findAll().stream()
                    .filter(order -> order.getCustomerId().equals(customerId))
                    .map(MallOrder::getId).toList();
            orderIds.forEach(id -> afterSaleRepository.findByOrderIdOrderByIdDesc(id)
                    .forEach(afterSaleRepository::delete));
            refundRepository.findAll().stream()
                    .filter(refund -> orderIds.contains(refund.getOrderId()))
                    .forEach(refundRepository::delete);
            orderItemRepository.findAll().stream().filter(item -> item.getGoodsId().equals(goodsId))
                    .forEach(orderItemRepository::delete);
            orderRepository.findAll().stream().filter(order -> order.getCustomerId().equals(customerId))
                    .forEach(orderRepository::delete);
            stockLogRepository.findAll().stream().filter(log -> log.getSkuId().equals(skuId))
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
    @DisplayName("用例1:仅退款(待发货)→ 商家同意 → 退款 + 回补库存 + 订单回退")
    void refundOnlyFlowRefundsAndRestoresStock() {
        OrderCreateResponse order = paidOrder(2);
        Long itemId = orderItemId(order.orderId());

        Long afterSaleId = afterSaleService.apply(new AfterSaleApplyRequest(itemId,
                MallAfterSale.TYPE_REFUND_ONLY, "不想要了", "拍错了", new BigDecimal("200.00"), null));
        assertThat(afterSaleStatus(afterSaleId)).isEqualTo(MallAfterSale.STATUS_PENDING);
        assertThat(orderStatus(order.orderId())).as("申请后订单进入售后中").isEqualTo(MallOrder.STATUS_AFTER_SALE);

        asStaff(() -> {
            afterSaleService.approve(afterSaleId, null);
            return null;
        });

        assertThat(afterSaleStatus(afterSaleId)).isEqualTo(MallAfterSale.STATUS_DONE);
        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            assertThat(sku.getStock()).as("退款后货回到可售库存").isEqualTo(10);
            assertThat(refundRepository.findByAfterSaleIdOrderByIdAsc(afterSaleId))
                    .as("终态必须创建退款记录").hasSize(1);
            return null;
        });
        // 整单唯一明细都退完 → 订单终态为已取消(3.9)
        assertThat(orderStatus(order.orderId())).isEqualTo(MallOrder.STATUS_CANCELLED);
    }

    @Test
    @DisplayName("用例2:仅退款不能用于已发货订单(3.9 的入口限制)")
    void refundOnlyRejectedAfterShipped() {
        OrderCreateResponse order = paidOrder(1);
        Long itemId = orderItemId(order.orderId());
        // 直接推进到"待收货"模拟已发货。用原生 SQL:这里没有事务,改实体的字段不会被 flush
        jdbcTemplate.update("update mall_order set status = ?, ship_time = ? where id = ?",
                MallOrder.STATUS_PENDING_RECEIVE, LocalDateTime.now(), order.orderId());

        assertThatThrownBy(() -> afterSaleService.apply(new AfterSaleApplyRequest(itemId,
                MallAfterSale.TYPE_REFUND_ONLY, "不想要了", null, new BigDecimal("100.00"), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("退货退款");
    }

    @Test
    @DisplayName("用例3:退货退款全流程 → 买家退回 → 商家确认 → 退款并回补库存")
    void returnRefundFlow() {
        OrderCreateResponse order = paidOrder(1);
        Long itemId = orderItemId(order.orderId());
        Long afterSaleId = afterSaleService.apply(new AfterSaleApplyRequest(itemId,
                MallAfterSale.TYPE_RETURN_REFUND, "质量问题", null, new BigDecimal("100.00"), List.of("a.jpg")));

        asStaff(() -> {
            afterSaleService.approve(afterSaleId, null);
            return null;
        });
        assertThat(afterSaleStatus(afterSaleId)).isEqualTo(MallAfterSale.STATUS_WAIT_RETURN);

        afterSaleService.submitReturnLogistics(afterSaleId, "顺丰", "SF123456");
        assertThat(afterSaleStatus(afterSaleId)).isEqualTo(MallAfterSale.STATUS_WAIT_RECEIVE);

        asStaff(() -> {
            afterSaleService.confirmReturnReceived(afterSaleId, null, null, null);
            return null;
        });

        assertThat(afterSaleStatus(afterSaleId)).isEqualTo(MallAfterSale.STATUS_DONE);
        inTenant(() -> {
            assertThat(skuRepository.findById(skuId).orElseThrow().getStock()).isEqualTo(10);
            assertThat(refundRepository.findByAfterSaleIdOrderByIdAsc(afterSaleId)).hasSize(1);
            return null;
        });
    }

    @Test
    @DisplayName("用例4:商家拒绝 → 买家申请客服介入 → 仲裁通过 → 退款")
    void arbitrationPassRefunds() {
        OrderCreateResponse order = paidOrder(1);
        Long itemId = orderItemId(order.orderId());
        Long afterSaleId = afterSaleService.apply(new AfterSaleApplyRequest(itemId,
                MallAfterSale.TYPE_REFUND_ONLY, "商品与描述不符", null, new BigDecimal("100.00"), null));

        asStaff(() -> {
            afterSaleService.reject(afterSaleId, "商品描述无误");
            return null;
        });
        assertThat(afterSaleStatus(afterSaleId)).isEqualTo(MallAfterSale.STATUS_REJECTED);

        afterSaleService.requestArbitration(afterSaleId);
        assertThat(afterSaleStatus(afterSaleId)).isEqualTo(MallAfterSale.STATUS_ARBITRATING);

        asStaff(() -> {
            afterSaleService.arbitrate(afterSaleId, true, "商家描述存在歧义,支持买家");
            return null;
        });

        assertThat(afterSaleStatus(afterSaleId)).isEqualTo(MallAfterSale.STATUS_ARBITRATION_PASS);
        inTenant(() -> {
            assertThat(refundRepository.findByAfterSaleIdOrderByIdAsc(afterSaleId)).hasSize(1);
            assertThat(skuRepository.findById(skuId).orElseThrow().getStock()).isEqualTo(10);
            return null;
        });
    }

    @Test
    @DisplayName("用例5:商家超时未处理 → 系统自动同意并退款(3.9 的 72 小时规则)")
    void autoApproveOnTimeout() {
        OrderCreateResponse order = paidOrder(1);
        Long itemId = orderItemId(order.orderId());
        Long afterSaleId = afterSaleService.apply(new AfterSaleApplyRequest(itemId,
                MallAfterSale.TYPE_REFUND_ONLY, "不想要了", null, new BigDecimal("100.00"), null));

        asStaff(() -> {
            // 把最后变更时间推到超时之前(update_time 是自动维护的,必须用原生 SQL 改)
            jdbcTemplate.update("update mall_after_sale set update_time = ? where id = ?",
                    LocalDateTime.now().minusHours(100), afterSaleId);
            return null;
        });
        int count = asStaff(() -> afterSaleService.autoApproveTimeout(LocalDateTime.now().minusHours(72)));

        assertThat(count).isGreaterThanOrEqualTo(1);
        assertThat(afterSaleStatus(afterSaleId)).isEqualTo(MallAfterSale.STATUS_DONE);
        inTenant(() -> {
            assertThat(skuRepository.findById(skuId).orElseThrow().getStock()).isEqualTo(10);
            return null;
        });
    }

    // ---------------------------------------------------------------- 辅助

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** 下单并支付(支付后订单进入待发货,这是仅退款的合法时机)。 */
    private OrderCreateResponse paidOrder(int quantity) {
        OrderCreateResponse order = orderService.create(
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, quantity)), addressId, null, null));
        payService.handlePayCallback(order.orderNo(), "wx-as-" + System.nanoTime(), order.payAmount(), true, "{}");
        return order;
    }

    private Long orderItemId(Long orderId) {
        return inTenant(() -> orderItemRepository.findByOrderIdOrderByIdAsc(orderId).stream()
                .findFirst().map(MallOrderItem::getId).orElseThrow());
    }

    private int afterSaleStatus(Long afterSaleId) {
        return inTenant(() -> afterSaleRepository.findById(afterSaleId).orElseThrow().getStatus());
    }

    private int orderStatus(Long orderId) {
        return inTenant(() -> orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    private Long paidOrderId() {
        return inTenant(() -> orderRepository.findAll().stream()
                .filter(order -> order.getCustomerId().equals(customerId))
                .map(MallOrder::getId).findFirst().orElse(-1L));
    }

    private void asBuyer() {
        ClientContext.set(new ClientPrincipal(TENANT_ID, customerId));
        TenantContext.setTenantId(TENANT_ID);
    }

    private <T> T asStaff(Supplier<T> action) {
        return inTenant(() -> {
            AuditContext.bind(new AuditContext(TENANT_ID, STAFF_ID, "127.0.0.1", "as-it"));
            try {
                TenantContext.setSuperUser(false);
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private <T> T inTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, false, () -> {
            AuditContext.bind(new AuditContext(TENANT_ID, STAFF_ID, "127.0.0.1", "as-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }
}
