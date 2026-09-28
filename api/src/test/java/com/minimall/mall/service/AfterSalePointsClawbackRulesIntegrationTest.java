package com.minimall.mall.service;

import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.domain.MallAfterSale;
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

/**
 * 售后退款扣回的规则(商城设计文档 3.11)。
 *
 * <p>直接调服务而不是走完整的售后流程:这条链路的正确性取决于四个独立规则(按比例、换货不扣、
 * 幂等、累计封顶),混在"申请 → 同意 → 退货 → 确认收货"的长流程里,一个断言失败分不清是哪条错了。
 * 与售后流程的接线由 {@code MallAfterSaleFlowIntegrationTest} 的两个用例覆盖。
 *
 * <p>夹具:2 件 × 50.00 = 100.00 元实付,发放 100 积分与 100 成长值。
 */
class AfterSalePointsClawbackRulesIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private MemberPointsService memberPointsService;
    @Autowired
    private MallPointsBatchRepository batchRepository;

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

    @Test
    @DisplayName("按退款金额占比扣回:退 30% 就扣 30%")
    void clawBackIsProportional() {
        Long orderId = orderWithGrant();

        int clawed = inTenant(() -> memberPointsService.clawBack(nextAfterSaleId(), orderId, customerId,
                new BigDecimal("30.00"), MallAfterSale.TYPE_REFUND_ONLY));

        assertThat(clawed).isEqualTo(30);
        assertThat(pointsOf(customerId)).isEqualTo(70);
        assertThat(growthOf(customerId)).isEqualTo(70);
        assertThat(batchSum(customerId)).isEqualTo(70);
    }

    @Test
    @DisplayName("同一售后单只扣一次")
    void clawBackIsIdempotentPerAfterSale() {
        Long orderId = orderWithGrant();
        long afterSaleId = nextAfterSaleId();

        assertThat(inTenant(() -> memberPointsService.clawBack(afterSaleId, orderId, customerId,
                new BigDecimal("50.00"), MallAfterSale.TYPE_REFUND_ONLY))).isEqualTo(50);
        assertThat(inTenant(() -> memberPointsService.clawBack(afterSaleId, orderId, customerId,
                new BigDecimal("50.00"), MallAfterSale.TYPE_REFUND_ONLY)))
                .as("重复处理同一售后单不能再扣")
                .isZero();

        assertThat(pointsOf(customerId)).isEqualTo(50);
    }

    @Test
    @DisplayName("多笔部分退款累计不超过发放值")
    void clawBackNeverExceedsGranted() {
        Long orderId = orderWithGrant();

        inTenant(() -> memberPointsService.clawBack(nextAfterSaleId(), orderId, customerId,
                new BigDecimal("30.00"), MallAfterSale.TYPE_REFUND_ONLY));
        inTenant(() -> memberPointsService.clawBack(nextAfterSaleId(), orderId, customerId,
                new BigDecimal("30.00"), MallAfterSale.TYPE_REFUND_ONLY));
        int last = inTenant(() -> memberPointsService.clawBack(nextAfterSaleId(), orderId, customerId,
                new BigDecimal("100.00"), MallAfterSale.TYPE_REFUND_ONLY));

        // 30 + 30 + 100 = 160 > 发放的 100,最后一次只能扣到剩的 40
        assertThat(last).isEqualTo(40);
        assertThat(pointsOf(customerId)).isZero();
        assertThat(growthOf(customerId)).isZero();
    }

    @Test
    @DisplayName("换货不扣:钱没退,交易仍然成立")
    void exchangeClawsNothing() {
        Long orderId = orderWithGrant();

        int clawed = inTenant(() -> memberPointsService.clawBack(nextAfterSaleId(), orderId, customerId,
                new BigDecimal("100.00"), MallAfterSale.TYPE_EXCHANGE));

        assertThat(clawed).isZero();
        assertThat(pointsOf(customerId)).as("换货不动积分").isEqualTo(100);
        assertThat(growthOf(customerId)).isEqualTo(100);
    }

    @Test
    @DisplayName("积分已经花光时扣到 0 为止,不产生负积分;成长值仍按应扣量扣")
    void clawBackStopsAtZero() {
        Long orderId = orderWithGrant();
        // 把积分花掉,但成长值保持不动(积分的消耗不影响成长值,这正是两者分账的意义)
        inTenantRun(() -> memberPointsService.manualAdjust(customerId, -100, 0, "花光"));
        assertThat(pointsOf(customerId)).isZero();
        assertThat(growthOf(customerId)).isEqualTo(100);

        int clawed = inTenant(() -> memberPointsService.clawBack(nextAfterSaleId(), orderId, customerId,
                new BigDecimal("100.00"), MallAfterSale.TYPE_REFUND_ONLY));

        assertThat(clawed).as("没得扣就是 0").isZero();
        assertThat(pointsOf(customerId)).as("不允许扣成负数").isZero();
        assertThat(growthOf(customerId)).as("成长值代表历史贡献,不该因为积分花光就不扣").isZero();
    }

    @Test
    @DisplayName("还没确认收货就退款:该订单没发过积分,直接跳过")
    void clawBackSkipsWhenNeverGranted() {
        // 只下单不发放
        OrderCreateResponse order = createOrder(customerId, addressId, 2);
        createdOrderIds.add(order.orderId());

        int clawed = inTenant(() -> memberPointsService.clawBack(nextAfterSaleId(), order.orderId(), customerId,
                new BigDecimal("100.00"), MallAfterSale.TYPE_REFUND_ONLY));

        assertThat(clawed).isZero();
        assertThat(pointsOf(customerId)).isZero();
    }

    // ---------------------------------------------------------------- 辅助

    /** 建一个 100.00 元的订单并发放 100 积分 + 100 成长值。 */
    private Long orderWithGrant() {
        OrderCreateResponse order = createOrder(customerId, addressId, 2);
        createdOrderIds.add(order.orderId());
        inTenant(() -> memberPointsService.grant(customerId, order.orderId(), order.payAmount()));
        assertThat(pointsOf(customerId)).isEqualTo(100);
        return order.orderId();
    }

    /** inTenant 收 Supplier,void 方法用这个包一层。 */
    private void inTenantRun(Runnable action) {
        inTenant(() -> {
            action.run();
            return null;
        });
    }

    private long afterSaleSeq;

    /** 售后单 ID 只需要唯一,不需要真实存在(扣回只拿它做幂等键)。 */
    private long nextAfterSaleId() {
        return 900000L + (++afterSaleSeq);
    }

    private int pointsOf(Long id) {
        return inTenant(() -> customerRepository.findById(id).orElseThrow().getPoints());
    }

    private int growthOf(Long id) {
        return inTenant(() -> customerRepository.findById(id).orElseThrow().getGrowthValue());
    }

    private int batchSum(Long id) {
        Long sum = inTenant(() -> batchRepository.sumUsableRemain(id, TENANT_ID, LocalDateTime.now()));
        return sum == null ? 0 : sum.intValue();
    }
}
