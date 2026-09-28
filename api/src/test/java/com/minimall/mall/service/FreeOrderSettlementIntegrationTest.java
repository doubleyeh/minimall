package com.minimall.mall.service;

import com.minimall.mall.api.dto.CouponSaveRequest;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.domain.MallCoupon;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderStatusLog;
import com.minimall.mall.domain.MallWxPayment;
import com.minimall.mall.domain.repository.MallCouponRecordRepository;
import com.minimall.mall.domain.repository.MallCouponRepository;
import com.minimall.mall.domain.repository.MallOrderStatusLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0 元订单自动置为已支付(商城设计文档 3.3)。
 *
 * <p>满减/优惠券能把实付打到 0(积分抵现不行 —— 它最多抵商品金额的 50%)。这种订单不走支付渠道,
 * 但**不能停在"待支付"**:`prepay` 对它直接拒绝,用户付不掉,只能等超时关单 ——
 * 那等于"下单成功但永远拿不到货"。
 *
 * <p>夹具里 2 件 × 50.00 = 100.00,所以用一张"满 100 减 1000"的券把实付打到 0。
 */
class FreeOrderSettlementIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private CouponService couponService;
    @Autowired
    private PayService payService;
    @Autowired
    private MallOrderStatusLogRepository statusLogRepository;
    @Autowired
    private MallCouponRepository couponRepository;
    @Autowired
    private MallCouponRecordRepository couponRecordRepository;

    private Long couponId;

    @AfterEach
    void tearDownCoupon() {
        inTenant(() -> {
            couponRecordRepository.findAll().stream()
                    .filter(record -> couponId != null && couponId.equals(record.getCouponId()))
                    .forEach(couponRecordRepository::delete);
            if (couponId != null) {
                couponRepository.findById(couponId).ifPresent(couponRepository::delete);
            }
            return null;
        });
    }

    @Test
    @DisplayName("0 元订单下单后直接进待发货:库存已实扣、锁定已释放、销量已累计")
    void freeOrderIsPaidImmediately() {
        OrderCreateResponse order = createFreeOrder();

        assertThat(order.payAmount()).as("券把实付打到了 0").isEqualByComparingTo("0.00");

        inTenant(() -> {
            MallOrder reloaded = orderRepository.findById(order.orderId()).orElseThrow();
            assertThat(reloaded.getStatus())
                    .as("不能停在待支付 —— 那条路对 0 元订单是死的")
                    .isEqualTo(MallOrder.STATUS_PENDING_SHIP);
            assertThat(reloaded.getPayTime()).as("自动支付也要记支付时间").isNotNull();

            var sku = skuRepository.findById(skuId).orElseThrow();
            assertThat(sku.getStock()).as("实际库存要扣掉").isEqualTo(8);
            assertThat(sku.getLockedStock()).as("锁定要释放").isZero();
            assertThat(goodsRepository.findById(goodsId).orElseThrow().getSaleCount())
                    .as("销量在支付成功时累计")
                    .isEqualTo(2);

            MallWxPayment payment = paymentRepository.findFirstByOrderIdOrderByIdDesc(order.orderId())
                    .orElseThrow();
            assertThat(payment.getPayStatus())
                    .as("支付流水要落成已成功,否则对账时这笔订单看起来永远没付钱")
                    .isEqualTo(MallWxPayment.PAY_STATUS_SUCCESS);
            return null;
        });

        List<MallOrderStatusLog> logs = statusLogsOf(order.orderId());
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getRemark()).as("先记创建").isEqualTo("创建订单");
        assertThat(logs.get(1).getRemark()).as("再记自动支付").isEqualTo("0 元订单自动支付");
        assertThat(logs.get(1).getToStatus()).isEqualTo(MallOrder.STATUS_PENDING_SHIP);
    }

    @Test
    @DisplayName("非 0 元订单不受影响:仍是待支付,库存只是锁定")
    void normalOrderStillWaitsForPayment() {
        OrderCreateResponse order = createOrder(customerId, addressId, 2);

        inTenant(() -> {
            assertThat(orderRepository.findById(order.orderId()).orElseThrow().getStatus())
                    .isEqualTo(MallOrder.STATUS_PENDING_PAY);
            var sku = skuRepository.findById(skuId).orElseThrow();
            assertThat(sku.getStock()).as("没付款就不该动实际库存").isEqualTo(10);
            assertThat(sku.getLockedStock()).isEqualTo(2);
            return null;
        });
    }

    @Test
    @DisplayName("重复结算不会重复扣库存或重复累计销量")
    void settleFreeOrderIsIdempotent() {
        OrderCreateResponse order = createFreeOrder();

        // 模拟"兜底重试"再跑一次
        inTenant(() -> {
            payService.settleFreeOrder(order.orderId());
            return null;
        });

        inTenant(() -> {
            var sku = skuRepository.findById(skuId).orElseThrow();
            assertThat(sku.getStock()).as("不能扣两次").isEqualTo(8);
            assertThat(goodsRepository.findById(goodsId).orElseThrow().getSaleCount()).isEqualTo(2);
            return null;
        });
    }

    @Test
    @DisplayName("对非 0 元订单调用结算:什么都不做")
    void settleFreeOrderIgnoresPaidOrders() {
        OrderCreateResponse order = createOrder(customerId, addressId, 1);

        inTenant(() -> {
            payService.settleFreeOrder(order.orderId());
            return null;
        });

        inTenant(() -> {
            assertThat(orderRepository.findById(order.orderId()).orElseThrow().getStatus())
                    .as("不属于它管的订单不能被它改状态")
                    .isEqualTo(MallOrder.STATUS_PENDING_PAY);
            return null;
        });
    }

    // ---------------------------------------------------------------- 辅助

    /** 用一张盖住全额的券下单,实付为 0。 */
    private OrderCreateResponse createFreeOrder() {
        couponId = inTenant(() -> couponService.create(new CouponSaveRequest(
                "0元订单用例券" + System.nanoTime(), MallCoupon.TYPE_FULL_REDUCTION,
                new BigDecimal("1000.00"), null, new BigDecimal("100.00"), 10, 1,
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30), null)));
        Long recordId = asClient(customerId, () -> couponService.claim(couponId));

        return asClient(customerId, () -> orderService.create(
                new com.minimall.mall.api.dto.CreateOrderRequest(
                        List.of(new com.minimall.mall.api.dto.CreateOrderRequest.Item(skuId, 2)),
                        addressId, recordId, null, null)));
    }

    /** 状态日志按 ID 升序 —— 它没有排序字段,插入顺序就是 ID 顺序。 */
    private List<MallOrderStatusLog> statusLogsOf(Long orderId) {
        return inTenant(() -> statusLogRepository.findAll().stream()
                .filter(log -> orderId.equals(log.getOrderId()))
                .sorted(Comparator.comparing(MallOrderStatusLog::getId))
                .toList());
    }
}
