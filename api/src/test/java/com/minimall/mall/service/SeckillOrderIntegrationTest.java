package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.api.dto.SeckillActivitySaveRequest;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderItem;
import com.minimall.mall.domain.MallSeckillBuyer;
import com.minimall.mall.domain.MallSeckillItem;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallSeckillActivityRepository;
import com.minimall.mall.domain.repository.MallSeckillBuyerRepository;
import com.minimall.mall.domain.repository.MallSeckillItemRepository;
import com.minimall.mall.service.support.SeckillRedisGate;
import com.minimall.mall.service.support.SeckillStockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 秒杀下单(商城设计文档 3.12):价格口径、限购、取消回补、关单后复活、Redis 丢 key。
 *
 * <p>这里断言的是**具体数值**而不是"大于 0":库存是三分量,只有精确值能证明
 * {@code stock / locked / frozen / sold} 四个数彼此对得上。
 */
@Tag("integration")
class SeckillOrderIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillStockService seckillStockService;
    @Autowired
    private SeckillRedisGate gate;
    @Autowired
    private MallSeckillActivityRepository activityRepository;
    @Autowired
    private MallSeckillItemRepository seckillItemRepository;
    @Autowired
    private MallSeckillBuyerRepository buyerRepository;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private com.minimall.mall.service.support.SkuStockKeeper stockKeeper;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private Long activityId;
    private Long seckillItemId;

    @BeforeEach
    void setUpSeckill() {
        activityId = newActivity(new BigDecimal("9.90"), 5, 2);
        inTenant(() -> {
            seckillService.publish(activityId);
            seckillService.startDue();
            return null;
        });
        seckillItemId = inTenant(() -> seckillItemRepository
                .findByActivityIdOrderBySortOrderAscIdAsc(activityId).get(0).getId());
    }

    @AfterEach
    void tearDownSeckill() {
        inTenant(() -> {
            seckillItemRepository.findAll().forEach(item -> gate.clear(item.getId()));
            buyerRepository.deleteAll(buyerRepository.findAll());
            seckillItemRepository.deleteAll(seckillItemRepository.findAll());
            activityRepository.deleteAll(activityRepository.findAll());
            return null;
        });
    }

    @Test
    @DisplayName("秒杀单:价格取秒杀价,不参与满减/券/积分,明细带商品项 id")
    void seckillOrderUsesSeckillPrice() {
        OrderCreateResponse response = buy(customerId, 2);

        assertThat(response.goodsAmount()).isEqualByComparingTo("19.80");
        assertThat(response.payAmount()).isEqualByComparingTo("19.80");

        MallOrder order = inTenant(() -> orderRepository.findById(response.orderId()).orElseThrow());
        assertThat(order.getOrderType()).isEqualTo(MallOrder.TYPE_SECKILL);
        assertThat(order.getSeckillActivityId()).isEqualTo(activityId);
        assertThat(order.getPromotionDiscountAmount()).isEqualByComparingTo("0");
        assertThat(order.getCouponDiscountAmount()).isEqualByComparingTo("0");
        assertThat(order.getPointsUsed()).isZero();

        MallOrderItem item = inTenant(() -> orderItemRepository
                .findByOrderIdOrderByIdAsc(response.orderId()).get(0));
        assertThat(item.getPrice()).isEqualByComparingTo("9.90");
        assertThat(item.getSeckillItemId()).isEqualTo(seckillItemId);

        MallSku sku = sku();
        assertThat(sku.getStock()).as("下单只锁不扣").isEqualTo(10);
        assertThat(sku.getLockedStock()).isEqualTo(2);
        assertThat(sku.getFrozenStock()).as("冻结区转成锁定区").isEqualTo(3);
        assertThat(sku.availableStock()).isEqualTo(5);
        assertThat(seckillItem().getSoldCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("限购:超过每人限购直接拒绝,库存与配额都不动")
    void limitIsEnforced() {
        buy(customerId, 2);

        assertThatThrownBy(() -> buy(customerId, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("限购");

        assertThat(seckillItem().getSoldCount()).isEqualTo(2);
        assertThat(sku().getLockedStock()).isEqualTo(2);
    }

    @Test
    @DisplayName("取消订单:配额、冻结、限购账本、Redis 闸门全部回退")
    void cancelRestoresEverything() {
        OrderCreateResponse response = buy(customerId, 2);
        asClientRun(customerId, () -> orderService.cancel(response.orderId()));

        MallSku sku = sku();
        assertThat(sku.getLockedStock()).isZero();
        assertThat(sku.getFrozenStock()).isEqualTo(5);
        assertThat(sku.getStock()).isEqualTo(10);
        assertThat(seckillItem().getSoldCount()).isZero();
        assertThat(buyer().getBoughtCount()).isZero();
        assertThat(gate.remaining(seckillItemId)).isEqualTo(5);
    }

    @Test
    @DisplayName("Redis 两个 key 都丢:限购仍按数据库精确拒绝,且把 Redis 计数改回真值")
    void limitIsExactAfterRedisLoss() {
        buy(customerId, 2);
        redis.delete("seckill:stock:" + seckillItemId);
        redis.delete("seckill:bought:" + seckillItemId + ":" + customerId);

        assertThatThrownBy(() -> buy(customerId, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("限购");

        assertThat(seckillItem().getSoldCount()).as("数据库没被多扣").isEqualTo(2);
        assertThat(gate.remaining(seckillItemId)).as("补偿按真值修回").isEqualTo(3);
    }

    @Test
    @DisplayName("关单后钱又到账:实物库存扣掉,冻结不残留,可售不为负")
    void latePaymentReopenKeepsInventoryConsistent() {
        OrderCreateResponse response = buy(customerId, 2);
        asClientRun(customerId, () -> orderService.cancel(response.orderId()));

        inTenant(() -> {
            // reopen 在线上是支付回调事务里调用的,测试里要自己开一个事务(批量更新要求有事务)
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> seckillStockService.reopen(
                            seckillItemId, customerId, 2, response.orderId()));
            return null;
        });

        MallSku sku = sku();
        assertThat(sku.getStock()).isEqualTo(8);
        assertThat(sku.getLockedStock()).isZero();
        assertThat(sku.getFrozenStock()).isEqualTo(3);
        assertThat(sku.availableStock()).isGreaterThanOrEqualTo(0);
        assertThat(seckillItem().getSoldCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("活动结束后不能再下单")
    void endedActivityRejectsOrder() {
        jdbcTemplate.update("update mall_seckill_activity set end_time = ? where id = ?",
                LocalDateTime.now().minusSeconds(1), activityId);
        inTenant(() -> {
            seckillService.endDue();
            return null;
        });

        assertThatThrownBy(() -> buy(customerId, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已结束");
    }

    @Test
    @DisplayName("0 元秒杀:实付 0,走自动置为已支付")
    void zeroPriceOrderIsAutoPaid() {
        inTenant(() -> {
            seckillService.cancel(activityId);
            return null;
        });
        Long freeActivityId = newActivity(BigDecimal.ZERO, 2, 1);

        inTenant(() -> {
            seckillService.publish(freeActivityId);
            seckillService.startDue();
            return null;
        });
        Long freeItemId = inTenant(() -> seckillItemRepository
                .findByActivityIdOrderBySortOrderAscIdAsc(freeActivityId).get(0).getId());

        OrderCreateResponse response = asClient(customerId, () -> orderService.createSeckill(
                freeItemId, 1, addressId));

        assertThat(response.payAmount()).isEqualByComparingTo("0");
        assertThat(inTenant(() -> orderRepository.findById(response.orderId()).orElseThrow().getStatus()))
                .isEqualTo(MallOrder.STATUS_PENDING_SHIP);
    }

    @Test
    @DisplayName("已支付的秒杀单退货:库存回普通池,已占用配额不变")
    void refundReturnsStockButNotQuota() {
        OrderCreateResponse response = buy(customerId, 2);
        forceOrderStatus(response.orderId(), MallOrder.STATUS_PENDING_SHIP);

        // 支付时走的是实扣(与 markPaid 同一条),退货走的是回库(与售后终态同一条)
        inTenant(() -> {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        stockKeeper.deductStockOnPaid(skuId, goodsId, TENANT_ID, 2);
                        stockKeeper.restoreStock(skuId, goodsId, TENANT_ID, 2);
                    });
            return null;
        });

        MallSku sku = sku();
        assertThat(sku.getStock()).isEqualTo(10);
        assertThat(sku.getLockedStock()).isZero();
        assertThat(sku.getFrozenStock()).as("退货回的是普通库存,不回到秒杀冻结池").isEqualTo(3);
        assertThat(sku.availableStock()).isEqualTo(7);
        assertThat(seckillItem().getSoldCount()).as("已占用配额不退").isEqualTo(2);
        assertThat(buyer().getBoughtCount()).as("限购账本不退").isEqualTo(2);
    }

    // ------------------------------------------------------------------ 夹具

    private Long newActivity(BigDecimal price, int quota, int limit) {
        LocalDateTime start = LocalDateTime.now().minusMinutes(1);
        LocalDateTime end = LocalDateTime.now().plusHours(1);
        return inTenant(() -> seckillService.create(new SeckillActivitySaveRequest(
                "秒杀单测-" + System.nanoTime(), start, end, null, 0, List.of(
                new SeckillActivitySaveRequest.Item(skuId, price, quota, limit, 0)))));
    }

    private OrderCreateResponse buy(Long buyerId, int quantity) {
        return asClient(buyerId, () -> orderService.createSeckill(seckillItemId, quantity, addressId));
    }

    private MallSku sku() {
        return inTenant(() -> skuRepository.findById(skuId).orElseThrow());
    }

    private MallSeckillItem seckillItem() {
        return inTenant(() -> seckillItemRepository.findById(seckillItemId).orElseThrow());
    }

    private MallSeckillBuyer buyer() {
        return inTenant(() -> buyerRepository.findByItemIdAndCustomerId(seckillItemId, customerId).orElseThrow());
    }
}
