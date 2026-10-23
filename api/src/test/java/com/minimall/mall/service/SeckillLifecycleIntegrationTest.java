package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.mall.api.dto.SeckillActivitySaveRequest;
import com.minimall.mall.domain.MallSeckillActivity;
import com.minimall.mall.domain.MallSeckillItem;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallSeckillActivityRepository;
import com.minimall.mall.domain.repository.MallSeckillBuyerRepository;
import com.minimall.mall.domain.repository.MallSeckillItemRepository;
import com.minimall.mall.service.support.SeckillRedisGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 秒杀活动生命周期(商城设计文档 3.12):发布冻结、到点开始预热、结束/取消解冻。
 *
 * <p>断言的是库存三分量的具体数值,而不是"不为负" —— 只有精确值才能证明冻结与解冻没有多退少补。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class SeckillLifecycleIntegrationTest {

    private static final long TENANT_ID = 1L;

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private com.minimall.mall.domain.repository.MallGoodsRepository goodsRepository;
    @Autowired
    private com.minimall.mall.domain.repository.MallSkuRepository skuRepository;
    @Autowired
    private MallSeckillActivityRepository activityRepository;
    @Autowired
    private MallSeckillItemRepository itemRepository;
    @Autowired
    private MallSeckillBuyerRepository buyerRepository;
    @Autowired
    private SeckillRedisGate gate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long goodsId;
    private Long skuId;

    @AfterEach
    void cleanUp() {
        TenantContextHolder.run(() -> {
            itemRepository.findAll().forEach(item -> gate.clear(item.getId()));
            buyerRepository.deleteAll(buyerRepository.findAll());
            itemRepository.deleteAll(itemRepository.findAll());
            activityRepository.deleteAll(activityRepository.findAll());
            if (skuId != null) {
                skuRepository.findById(skuId).ifPresent(skuRepository::delete);
            }
            if (goodsId != null) {
                goodsRepository.findById(goodsId).ifPresent(goodsRepository::delete);
            }
            return null;
        });
    }

    @Test
    @DisplayName("发布即冻结:可售减少配额,冻结量等于配额,状态变待开始")
    void publishFreezesQuota() {
        setUpGoods(10);
        Long activityId = createActivity("冻结用例", 3, 2);

        int frozen = TenantContextHolder.run(() -> seckillService.publish(activityId));

        assertThat(frozen).isEqualTo(3);
        MallSku sku = sku();
        assertThat(sku.getStock()).isEqualTo(10);
        assertThat(sku.getLockedStock()).isZero();
        assertThat(sku.getFrozenStock()).isEqualTo(3);
        assertThat(sku.availableStock()).isEqualTo(7);
        assertThat(activity(activityId).getStatus()).isEqualTo(MallSeckillActivity.STATUS_PENDING);
    }

    @Test
    @DisplayName("到点开始:状态进行中,Redis 预热出剩余量;结束解冻剩余配额并清 key")
    void startAndEndReleaseRemaining() {
        setUpGoods(10);
        Long activityId = createActivity("开始结束用例", 4, 1);
        TenantContextHolder.run(() -> seckillService.publish(activityId));
        Long itemId = itemOf(activityId).getId();

        int started = TenantContextHolder.run(() -> seckillService.startDue());

        assertThat(started).isEqualTo(1);
        assertThat(activity(activityId).getStatus()).isEqualTo(MallSeckillActivity.STATUS_RUNNING);
        assertThat(gate.remaining(itemId)).as("预热后剩余量等于配额").isEqualTo(4);

        // 模拟时间流逝:把结束时间改到过去
        jdbcTemplate.update("update mall_seckill_activity set end_time = ? where id = ?",
                LocalDateTime.now().minusSeconds(1), activityId);
        int ended = TenantContextHolder.run(() -> seckillService.endDue());

        assertThat(ended).isEqualTo(1);
        assertThat(activity(activityId).getStatus()).isEqualTo(MallSeckillActivity.STATUS_ENDED);
        assertThat(sku().getFrozenStock()).isZero();
        assertThat(sku().availableStock()).isEqualTo(10);
        assertThat(gate.remaining(itemId)).as("结束后清 key").isNull();
    }

    @Test
    @DisplayName("取消待开始的活动:配额解冻,可售恢复")
    void cancelReleasesQuota() {
        setUpGoods(10);
        Long activityId = createActivity("取消用例", 6, 1);
        TenantContextHolder.run(() -> seckillService.publish(activityId));
        assertThat(sku().getFrozenStock()).isEqualTo(6);

        TenantContextHolder.run(() -> {
            seckillService.cancel(activityId);
            return null;
        });

        assertThat(activity(activityId).getStatus()).isEqualTo(MallSeckillActivity.STATUS_CANCELLED);
        assertThat(sku().getFrozenStock()).isZero();
        assertThat(sku().availableStock()).isEqualTo(10);
    }

    @Test
    @DisplayName("库存不够冻结:整次发布回滚,不留半个活动")
    void publishRollsBackWhenStockInsufficient() {
        setUpGoods(2);
        Long activityId = createActivity("库存不足用例", 5, 1);

        assertThatThrownBy(() -> TenantContextHolder.run(() -> seckillService.publish(activityId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("可售");

        assertThat(sku().getFrozenStock()).isZero();
        assertThat(activity(activityId).getStatus())
                .as("发布失败,状态仍是草稿").isEqualTo(MallSeckillActivity.STATUS_DRAFT);
    }

    @Test
    @DisplayName("同一 SKU 时间重叠的活动被拒绝")
    void overlappingActivityRejected() {
        setUpGoods(10);
        LocalDateTime start = LocalDateTime.now().minusMinutes(1);
        LocalDateTime end = LocalDateTime.now().plusHours(1);
        Long first = TenantContextHolder.run(() -> seckillService.create(
                new SeckillActivitySaveRequest("第一场", start, end, null, 0, List.of(
                        new SeckillActivitySaveRequest.Item(skuId, new BigDecimal("9.90"), 2, 1, 0)))));
        TenantContextHolder.run(() -> seckillService.publish(first));

        Long second = TenantContextHolder.run(() -> seckillService.create(
                new SeckillActivitySaveRequest("重叠场", start.plusMinutes(10), end.plusMinutes(10), null, 0,
                        List.of(new SeckillActivitySaveRequest.Item(skuId, new BigDecimal("8.80"), 2, 1, 0)))));

        assertThatThrownBy(() -> TenantContextHolder.run(() -> seckillService.publish(second)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重叠");
        assertThat(sku().getFrozenStock()).as("失败的那次不该冻结").isEqualTo(2);
    }

    @Test
    @DisplayName("时间不重叠的活动可以各自冻结、互不影响地解冻")
    void nonOverlappingActivitiesFreezeSeparately() {
        setUpGoods(10);
        LocalDateTime now = LocalDateTime.now();
        Long first = TenantContextHolder.run(() -> seckillService.create(
                new SeckillActivitySaveRequest("早场", now.minusMinutes(1), now.plusMinutes(30), null, 0,
                        List.of(new SeckillActivitySaveRequest.Item(skuId, new BigDecimal("9.90"), 3, 1, 0)))));
        Long second = TenantContextHolder.run(() -> seckillService.create(
                new SeckillActivitySaveRequest("晚场", now.plusMinutes(40), now.plusMinutes(90), null, 0,
                        List.of(new SeckillActivitySaveRequest.Item(skuId, new BigDecimal("8.80"), 4, 1, 0)))));
        TenantContextHolder.run(() -> seckillService.publish(first));
        TenantContextHolder.run(() -> seckillService.publish(second));

        assertThat(sku().getFrozenStock()).isEqualTo(7);
        assertThat(sku().availableStock()).isEqualTo(3);

        TenantContextHolder.run(() -> {
            seckillService.cancel(first);
            return null;
        });
        assertThat(sku().getFrozenStock()).as("只解冻被取消那一场").isEqualTo(4);
        assertThat(sku().availableStock()).isEqualTo(6);
    }

    // ------------------------------------------------------------------ 夹具

    private void setUpGoods(int stock) {
        goodsId = TenantContextHolder.run(() -> {
            com.minimall.mall.domain.MallGoods goods = new com.minimall.mall.domain.MallGoods();
            goods.setCategoryId(0L);
            goods.setGoodsName("秒杀测试商品");
            goods.setMainImage("https://example.com/s.png");
            goods.setSalePriceMin(new BigDecimal("50.00"));
            goods.setSalePriceMax(new BigDecimal("50.00"));
            goods.setTotalStock(stock);
            goods.setSaleCount(0);
            goods.setStatus(1);
            goods.setSortOrder(0);
            return goodsRepository.save(goods).getId();
        });
        skuId = TenantContextHolder.run(() -> {
            MallSku sku = new MallSku();
            sku.setGoodsId(goodsId);
            sku.setSkuCode("SK-" + System.nanoTime());
            sku.setSkuName("默认规格");
            sku.setPrice(new BigDecimal("50.00"));
            sku.setStock(stock);
            sku.setLockedStock(0);
            sku.setStatus(1);
            return skuRepository.save(sku).getId();
        });
    }

    private Long createActivity(String name, int quota, int limit) {
        LocalDateTime start = LocalDateTime.now().minusMinutes(1);
        LocalDateTime end = LocalDateTime.now().plusHours(1);
        return TenantContextHolder.run(() -> seckillService.create(
                new SeckillActivitySaveRequest(name, start, end, null, 0, List.of(
                        new SeckillActivitySaveRequest.Item(skuId, new BigDecimal("9.90"), quota, limit, 0)))));
    }

    private MallSku sku() {
        return TenantContextHolder.run(() -> skuRepository.findById(skuId).orElseThrow());
    }

    private MallSeckillActivity activity(Long id) {
        return TenantContextHolder.run(() -> activityRepository.findById(id).orElseThrow());
    }

    private MallSeckillItem itemOf(Long activityId) {
        return TenantContextHolder.run(() -> itemRepository.findByActivityIdOrderBySortOrderAscIdAsc(activityId)
                .get(0));
    }

    /** 测试里的租户上下文样板。 */
    private static final class TenantContextHolder {
        private static <T> T run(java.util.function.Supplier<T> action) {
            return com.minimall.infra.tenant.TenantContext.callAsTenant(TENANT_ID, false, action);
        }
    }
}
