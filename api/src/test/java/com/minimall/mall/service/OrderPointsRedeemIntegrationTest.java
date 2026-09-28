package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.infra.id.SnowflakeIdGenerator;
import com.minimall.mall.api.dto.CreateOrderRequest;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.api.dto.OrderPreviewRequest;
import com.minimall.mall.api.dto.OrderPreviewView;
import com.minimall.mall.domain.MallPointsBatch;
import com.minimall.mall.domain.repository.MallPointsBatchRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 下单积分抵现(商城设计文档 3.11)。
 *
 * <p>这里验的是**与订单流程的接线**:预扣发生在正确的事务里、金额落库正确、订单关闭时能原样退回。
 * 批次 FIFO、过期、滚动成长值这些算法本身的正确性由 {@link MemberPointsGrowthIntegrationTest} 负责。
 *
 * <p>夹具里的 SKU 单价 50.00,所以下单数量直接决定商品金额(2 件 = 100.00 元,积分上限 5000)。
 */
class OrderPointsRedeemIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private MemberPointsService memberPointsService;
    @Autowired
    private MallPointsBatchRepository batchRepository;

    /** 本类造出来的订单:这些表没有外键,靠它把占用明细清干净。 */
    private final List<Long> createdOrderIds = new ArrayList<>();

    @AfterEach
    void tearDownMemberData() {
        inTenant(() -> {
            for (Long orderId : createdOrderIds) {
                jdbcTemplate.update("delete from mall_points_use where tenant_id = ? and order_id = ?",
                        TENANT_ID, orderId);
            }
            for (Long id : new Long[] { customerId, otherCustomerId }) {
                if (id == null) {
                    continue;
                }
                jdbcTemplate.update("delete from mall_points_batch where tenant_id = ? and customer_id = ?",
                        TENANT_ID, id);
                jdbcTemplate.update("delete from mall_points_log where tenant_id = ? and customer_id = ?",
                        TENANT_ID, id);
                jdbcTemplate.update("delete from mall_growth_log where tenant_id = ? and customer_id = ?",
                        TENANT_ID, id);
            }
            return null;
        });
    }

    // ---------------------------------------------------------------- 预扣

    @Test
    @DisplayName("下单用积分:金额按结算顺序算,订单上留下用了多少分、抵了多少钱")
    void createOrderWithPoints() {
        grantPoints(5000);

        OrderCreateResponse order = createOrderWithPoints(2, 2000);

        // 2 件 × 50.00 = 100.00;2000 积分 = 20.00 元
        assertThat(order.goodsAmount()).isEqualByComparingTo("100.00");
        assertThat(order.pointsDiscountAmount()).isEqualByComparingTo("20.00");
        assertThat(order.payAmount()).isEqualByComparingTo("80.00");
        assertThat(order.pointsUsed()).isEqualTo(2000);

        assertThat(pointsOf(customerId)).as("预扣要从余额里扣掉").isEqualTo(3000);
        assertThat(usableBatchSum(customerId)).as("批次也必须同步,否则两者会漂移").isEqualTo(3000);
    }

    @Test
    @DisplayName("下单不用积分:一切照旧,订单上留 0")
    void createOrderWithoutPoints() {
        grantPoints(5000);

        OrderCreateResponse order = createOrderWithPoints(2, null);

        assertThat(order.pointsDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(order.payAmount()).isEqualByComparingTo("100.00");
        assertThat(order.pointsUsed()).isZero();
        assertThat(pointsOf(customerId)).as("不用就不动").isEqualTo(5000);
    }

    @Test
    @DisplayName("超过上限的积分请求直接拒绝,而不是静默夹取")
    void createOrderRejectsPointsOverCap() {
        grantPoints(10000);

        // 商品 100.00 元 → 上限 50 元 = 5000 分
        assertThatThrownBy(() -> createOrderWithPoints(2, 5001))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多可用");

        assertThat(pointsOf(customerId)).as("被拒绝时一分都不该扣").isEqualTo(10000);
        assertThat(usableBatchSum(customerId)).isEqualTo(10000);
    }

    @Test
    @DisplayName("可用积分不足时也拒绝(上限取的是两者里小的那个)")
    void createOrderRejectsWhenBalanceInsufficient() {
        grantPoints(300);

        assertThatThrownBy(() -> createOrderWithPoints(2, 500))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多可用");

        assertThat(pointsOf(customerId)).isEqualTo(300);
    }

    @Test
    @DisplayName("上限本身也在挡:积分多于上限时按商品金额的 50% 卡住")
    void capItselfIsEnforced() {
        // 客户有 10000 分,但商品 100 元的上限是 5000 分 —— 这一条让"上限"成为绑定约束,
        // 否则余额永远先被卡住,上限算错了也看不出来
        grantPoints(10000);

        OrderPreviewView preview = asClient(customerId, () -> orderService.preview(new OrderPreviewRequest(
                List.of(new CreateOrderRequest.Item(skuId, 2)), addressId, null, null)));
        assertThat(preview.maxRedeemPoints()).isEqualTo(5000);

        assertThatThrownBy(() -> createOrderWithPoints(2, 5001))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多可用");

        OrderCreateResponse accepted = createOrderWithPoints(2, 5000);
        assertThat(accepted.payAmount()).as("5000 分抵 50 元").isEqualByComparingTo("50.00");
        assertThat(pointsOf(customerId)).isEqualTo(5000);
    }

    // ---------------------------------------------------------------- 关闭退回

    @Test
    @DisplayName("买家取消:积分退回原批次,过期时间不变")
    void cancelReturnsPointsToOriginalBatch() {
        grantPoints(5000);
        Long batchId = batchIdsOf(customerId).get(0);
        LocalDateTime expireBefore = expireTimeOf(batchId);

        OrderCreateResponse order = createOrderWithPoints(2, 2000);
        assertThat(pointsOf(customerId)).isEqualTo(3000);

        asClientRun(customerId, () -> orderService.cancel(order.orderId()));

        assertThat(pointsOf(customerId)).isEqualTo(5000);
        assertThat(remainOf(batchId)).as("退回原批次").isEqualTo(5000);
        assertThat(expireTimeOf(batchId))
                .as("过期时间必须沿用原来的 —— 新建批次等于让积分无限续期")
                .isEqualTo(expireBefore);
    }

    @Test
    @DisplayName("超时关闭:走的是与取消同一段资源释放,积分同样退回")
    void timeoutCloseReturnsPointsToo() {
        grantPoints(5000);
        OrderCreateResponse order = createOrderWithPoints(2, 2000);
        assertThat(pointsOf(customerId)).isEqualTo(3000);

        // 截止时间给到未来,等价于"这批订单全都超时了"
        inTenant(() -> orderService.closeTimeoutOrders(LocalDateTime.now().plusMinutes(1)));

        assertThat(pointsOf(customerId)).as("超时关闭与取消必须做完全一样的事").isEqualTo(5000);
        assertThat(usableBatchSum(customerId)).isEqualTo(5000);
    }

    // ---------------------------------------------------------------- 试算

    @Test
    @DisplayName("试算与下单的金额逐分一致 —— 否则'端上看到的价'就是错的")
    void previewMatchesCreateAmounts() {
        grantPoints(5000);

        OrderPreviewView preview = asClient(customerId, () -> orderService.preview(new OrderPreviewRequest(
                List.of(new CreateOrderRequest.Item(skuId, 2)), addressId, null, 2000)));
        OrderCreateResponse created = createOrderWithPoints(2, 2000);

        assertThat(preview.goodsAmount()).isEqualByComparingTo(created.goodsAmount());
        assertThat(preview.promotionDiscountAmount()).isEqualByComparingTo(created.promotionDiscountAmount());
        assertThat(preview.couponDiscountAmount()).isEqualByComparingTo(created.couponDiscountAmount());
        assertThat(preview.pointsDiscountAmount()).isEqualByComparingTo(created.pointsDiscountAmount());
        assertThat(preview.freightAmount()).isEqualByComparingTo(created.freightAmount());
        assertThat(preview.payAmount()).isEqualByComparingTo(created.payAmount());
        assertThat(preview.pointsUsed()).isEqualTo(created.pointsUsed());
    }

    @Test
    @DisplayName("试算只读:不落单、不占库存、不扣积分")
    void previewHasNoSideEffects() {
        grantPoints(5000);
        int lockedBefore = inTenant(() -> skuRepository.findById(skuId).orElseThrow().getLockedStock());

        OrderPreviewView preview = asClient(customerId, () -> orderService.preview(new OrderPreviewRequest(
                List.of(new CreateOrderRequest.Item(skuId, 2)), addressId, null, 2000)));

        assertThat(preview.pointsUsed()).as("试算要如实反映'用了多少分'").isEqualTo(2000);
        assertThat(preview.maxRedeemPoints()).isEqualTo(5000);
        assertThat(pointsOf(customerId)).as("试算不能真扣积分").isEqualTo(5000);
        assertThat(usableBatchSum(customerId)).isEqualTo(5000);
        assertThat(inTenant(() -> skuRepository.findById(skuId).orElseThrow().getLockedStock()))
                .as("试算不能锁库存")
                .isEqualTo(lockedBefore);
    }

    @Test
    @DisplayName("还没选地址也能试算:运费按 0 计并置 needAddress")
    void previewWithoutAddressIsAllowed() {
        grantPoints(5000);

        OrderPreviewView preview = asClient(customerId, () -> orderService.preview(new OrderPreviewRequest(
                List.of(new CreateOrderRequest.Item(skuId, 2)), null, null, null)));

        assertThat(preview.needAddress()).isTrue();
        assertThat(preview.freightAmount()).isEqualByComparingTo("0.00");
        assertThat(preview.goodsAmount()).isEqualByComparingTo("100.00");
    }

    // ---------------------------------------------------------------- 辅助

    private void grantPoints(int points) {
        inTenant(() -> memberPointsService.grant(customerId, SnowflakeIdGenerator.nextId(),
                new BigDecimal(points)));
    }

    private OrderCreateResponse createOrderWithPoints(int quantity, Integer pointsToUse) {
        OrderCreateResponse created = asClient(customerId, () -> orderService.create(new CreateOrderRequest(
                List.of(new CreateOrderRequest.Item(skuId, quantity)), addressId, null, pointsToUse, null)));
        createdOrderIds.add(created.orderId());
        return created;
    }

    private int pointsOf(Long id) {
        return inTenant(() -> customerRepository.findById(id).orElseThrow().getPoints());
    }

    private int usableBatchSum(Long id) {
        Long sum = inTenant(() -> batchRepository.sumUsableRemain(id, TENANT_ID, LocalDateTime.now()));
        return sum == null ? 0 : sum.intValue();
    }

    private List<Long> batchIdsOf(Long id) {
        return inTenant(() -> batchRepository.findUsable(id, TENANT_ID, LocalDateTime.now()).stream()
                .map(MallPointsBatch::getId)
                .toList());
    }

    private int remainOf(Long batchId) {
        return inTenant(() -> batchRepository.findById(batchId).orElseThrow().getRemainPoints());
    }

    private LocalDateTime expireTimeOf(Long batchId) {
        return inTenant(() -> batchRepository.findById(batchId).orElseThrow().getExpireTime());
    }
}
