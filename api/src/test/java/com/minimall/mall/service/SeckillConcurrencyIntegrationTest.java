package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.api.dto.SeckillActivitySaveRequest;
import com.minimall.mall.domain.MallCustomerAddress;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallSeckillActivityRepository;
import com.minimall.mall.domain.repository.MallSeckillBuyerRepository;
import com.minimall.mall.domain.repository.MallSeckillItemRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.infra.auth.ClientPrincipal;
import com.minimall.mall.service.support.SeckillRedisGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 秒杀并发(商城设计文档 3.12):多人抢少量配额。
 *
 * <p>断言"成功数恰好等于配额"而不是"不超过配额":少于配额说明闸门或行锁过头把正常用户挡住了,
 * 这两种错误的表现都是"活动卖不完",比超卖更难被发现。
 */
@Tag("integration")
class SeckillConcurrencyIntegrationTest extends MallClientServiceTestBase {

    private static final int QUOTA = 5;
    private static final int BUYERS = 16;

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillRedisGate gate;
    @Autowired
    private MallSeckillActivityRepository activityRepository;
    @Autowired
    private MallSeckillItemRepository seckillItemRepository;
    @Autowired
    private MallSeckillBuyerRepository buyerRepository;

    private Long activityId;
    private Long seckillItemId;
    private final List<Long> buyerIds = new ArrayList<>();
    private final List<Long> buyerAddressIds = new ArrayList<>();

    @BeforeEach
    void setUpBuyers() {
        inTenant(() -> {
            for (int i = 0; i < BUYERS; i++) {
                Long id = newCustomer("it-seckill-" + i + "-" + System.nanoTime());
                buyerIds.add(id);
                MallCustomerAddress address = new MallCustomerAddress();
                address.setCustomerId(id);
                address.setReceiverName("抢购用户" + i);
                address.setReceiverPhone("13900000000");
                address.setProvince("广东省");
                address.setCity("深圳市");
                address.setDistrict("南山区");
                address.setDetailAddress("测试路 " + i + " 号");
                address.setIsDefault(1);
                buyerAddressIds.add(addressRepository.save(address).getId());
            }
            activityId = seckillService.create(new SeckillActivitySaveRequest(
                    "并发秒杀-" + System.nanoTime(), LocalDateTime.now().minusMinutes(1),
                    LocalDateTime.now().plusHours(1), null, 0, List.of(
                    new SeckillActivitySaveRequest.Item(skuId, new BigDecimal("9.90"), QUOTA, 1, 0))));
            seckillService.publish(activityId);
            seckillService.startDue();
            seckillItemId = seckillItemRepository
                    .findByActivityIdOrderBySortOrderAscIdAsc(activityId).get(0).getId();
            return null;
        });
    }

    @AfterEach
    void tearDownBuyers() {
        inTenant(() -> {
            for (Long id : buyerIds) {
                orderRepository.findAll().stream()
                        .filter(order -> id.equals(order.getCustomerId()))
                        .forEach(order -> {
                            orderItemRepository.findByOrderIdOrderByIdAsc(order.getId())
                                    .forEach(orderItemRepository::delete);
                            paymentRepository.findFirstByOrderIdOrderByIdDesc(order.getId())
                                    .ifPresent(paymentRepository::delete);
                            orderRepository.delete(order);
                        });
                addressRepository.deleteAll(addressRepository.findByCustomerIdOrderByIsDefaultDescIdDesc(id));
                customerRepository.findById(id).ifPresent(customerRepository::delete);
            }
            seckillItemRepository.findAll().forEach(item -> gate.clear(item.getId()));
            buyerRepository.deleteAll(buyerRepository.findAll());
            seckillItemRepository.deleteAll(seckillItemRepository.findAll());
            activityRepository.deleteAll(activityRepository.findAll());
            return null;
        });
    }

    @Test
    @DisplayName("16 人抢 5 件:成功恰好 5 笔,库存三分量与已售全部对得上")
    void exactlyQuotaSucceedsUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(BUYERS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(BUYERS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();

        for (int i = 0; i < BUYERS; i++) {
            Long buyerId = buyerIds.get(i);
            Long addressId = buyerAddressIds.get(i);
            pool.submit(() -> {
                try {
                    start.await();
                    ClientContext.callAs(new ClientPrincipal(TENANT_ID, buyerId),
                            () -> TenantContext.callAsTenant(TENANT_ID, false, () -> {
                                try {
                                    orderService.createSeckill(seckillItemId, 1, addressId);
                                    success.incrementAndGet();
                                } catch (BusinessException ex) {
                                    soldOut.incrementAndGet();
                                }
                                return null;
                            }));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(success.get()).as("成功数必须恰好等于配额").isEqualTo(QUOTA);
        assertThat(soldOut.get()).isEqualTo(BUYERS - QUOTA);
        assertThat(inTenant(() -> seckillItemRepository.findById(seckillItemId).orElseThrow().getSoldCount()))
                .isEqualTo(QUOTA);

        MallSku sku = inTenant(() -> skuRepository.findById(skuId).orElseThrow());
        assertThat(sku.getStock()).isEqualTo(10);
        assertThat(sku.getLockedStock()).isEqualTo(QUOTA);
        assertThat(sku.getFrozenStock()).isZero();
        assertThat(sku.availableStock()).isEqualTo(10 - QUOTA);
        assertThat(gate.remaining(seckillItemId)).as("闸门剩余应归零").isZero();
    }

    @Test
    @DisplayName("同一个用户连点 8 次:只有 1 件成交(限购在闸门就被挡住)")
    void sameUserRapidClicksRespectLimit() throws Exception {
        Long buyerId = buyerIds.get(0);
        Long addressId = buyerAddressIds.get(0);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ClientContext.callAs(new ClientPrincipal(TENANT_ID, buyerId),
                            () -> TenantContext.callAsTenant(TENANT_ID, false, () -> {
                                try {
                                    orderService.createSeckill(seckillItemId, 1, addressId);
                                    success.incrementAndGet();
                                } catch (BusinessException ex) {
                                    // 售罄或超限都算被挡住
                                }
                                return null;
                            }));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(success.get()).as("每人限购 1 件,连点也不能多买").isEqualTo(1);
        assertThat(inTenant(() -> buyerRepository.findByItemIdAndCustomerId(seckillItemId, buyerId)
                .orElseThrow().getBoughtCount())).isEqualTo(1);
        assertThat(inTenant(() -> seckillItemRepository.findById(seckillItemId).orElseThrow().getSoldCount()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("普通单不能吃掉秒杀冻结的库存")
    void normalOrderCannotConsumeFrozenStock() {
        // 库存 10,冻结 5,普通可售只有 5
        assertThatThrownBy(() -> createOrder(customerId, addressId, 6))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");

        MallSku sku = inTenant(() -> skuRepository.findById(skuId).orElseThrow());
        assertThat(sku.getLockedStock()).isZero();
        assertThat(sku.getFrozenStock()).isEqualTo(QUOTA);
    }
}
