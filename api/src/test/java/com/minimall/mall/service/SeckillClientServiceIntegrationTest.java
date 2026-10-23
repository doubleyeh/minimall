package com.minimall.mall.service;

import com.minimall.mall.api.dto.ClientSeckillHomeView;
import com.minimall.mall.api.dto.ClientSeckillItemView;
import com.minimall.mall.api.dto.SeckillActivitySaveRequest;
import com.minimall.mall.api.dto.SeckillPreviewView;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.domain.MallSeckillItem;
import com.minimall.mall.domain.repository.MallSeckillActivityRepository;
import com.minimall.mall.domain.repository.MallSeckillBuyerRepository;
import com.minimall.mall.domain.repository.MallSeckillItemRepository;
import com.minimall.mall.service.support.SeckillRedisGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 小程序端秒杀浏览(商城设计文档 3.12):分组、剩余量、已购件数、结算试算。
 *
 * <p>这里的重点是"端上看到的数字与后端真实值一致":分组按服务端时钟、剩余量取自闸门、
 * 已购件数取自限购账本 —— 端上自己算任何一个都会和下单结果对不上。
 */
@Tag("integration")
class SeckillClientServiceIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private SeckillClientService seckillClientService;
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

    @BeforeEach
    void setUpSeckill() {
        activityId = inTenant(() -> seckillService.create(new SeckillActivitySaveRequest(
                "浏览用例-" + System.nanoTime(), LocalDateTime.now().minusMinutes(1),
                LocalDateTime.now().plusHours(1), null, 0, List.of(
                new SeckillActivitySaveRequest.Item(skuId, new BigDecimal("9.90"), 4, 2, 0)))));
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
            gate.clearHome(com.minimall.infra.tenant.TenantContext.getTenantId());
            buyerRepository.deleteAll(buyerRepository.findAll());
            seckillItemRepository.deleteAll(seckillItemRepository.findAll());
            activityRepository.deleteAll(activityRepository.findAll());
            return null;
        });
    }

    @Test
    @DisplayName("列表:按服务端时钟分成进行中/即将开始,剩余量取闸门")
    void homeGroupsByServerClock() {
        ClientSeckillHomeView home = asClient(customerId, () -> seckillClientService.home());

        assertThat(home.running()).hasSize(1);
        assertThat(home.upcoming()).isEmpty();
        ClientSeckillItemView view = home.running().get(0);
        assertThat(view.itemId()).isEqualTo(seckillItemId);
        assertThat(view.remaining()).isEqualTo(4);
        assertThat(view.bought()).isZero();
        assertThat(view.perCustomerLimit()).isEqualTo(2);
    }

    @Test
    @DisplayName("详情:抢购后剩余量与已购件数跟着变")
    void detailReflectsRemainingAndBought() {
        OrderCreateResponse order = asClient(customerId,
                () -> orderService.createSeckill(seckillItemId, 2, addressId));

        ClientSeckillItemView detail = asClient(customerId, () -> seckillClientService.detail(seckillItemId));

        assertThat(detail.remaining()).isEqualTo(2);
        assertThat(detail.bought()).isEqualTo(2);
        assertThat(detail.soldCount()).isEqualTo(2);
        assertThat(order.payAmount()).isEqualByComparingTo("19.80");
    }

    @Test
    @DisplayName("试算:金额由服务端算,秒杀价与运费口径与下单一致")
    void previewMatchesRealOrder() {
        SeckillPreviewView preview = asClient(customerId,
                () -> orderService.previewSeckill(seckillItemId, addressId, 3));

        assertThat(preview.goodsAmount()).isEqualByComparingTo("29.70");
        assertThat(preview.payAmount()).isEqualByComparingTo("29.70");
        assertThat(preview.remaining()).isEqualTo(4);
        assertThat(preview.needAddress()).isFalse();
    }

    @Test
    @DisplayName("限购已满时详情页给出已购件数,抢购按钮据此置灰")
    void limitReachedShowsBoughtCount() {
        asClient(customerId, () -> orderService.createSeckill(seckillItemId, 2, addressId));

        ClientSeckillItemView detail = asClient(customerId, () -> seckillClientService.detail(seckillItemId));

        assertThat(detail.bought()).isEqualTo(detail.perCustomerLimit());
        MallSeckillItem item = inTenant(() -> seckillItemRepository.findById(seckillItemId).orElseThrow());
        assertThat(item.remaining()).isEqualTo(2);
    }
}
