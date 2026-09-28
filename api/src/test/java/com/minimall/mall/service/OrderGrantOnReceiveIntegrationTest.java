package com.minimall.mall.service;

import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallPointsLog;
import com.minimall.mall.domain.repository.MallPointsBatchRepository;
import com.minimall.mall.domain.repository.MallPointsLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 确认收货发放积分与成长值(商城设计文档 3.4)。
 *
 * <p><b>两条路径必须各测一条</b>:买家手动确认与系统自动确认原本各写一份"改状态 + 写日志",
 * 没有共用方法。发积分只加在其中一条上会静默漏发 —— 而自动确认每小时都在跑,
 * 漏了要等用户投诉才发现。
 *
 * <p>夹具里的 SKU 单价 50.00,所以"实付 → 积分"的换算很直观;取整口径单独用 90.50 验。
 */
class OrderGrantOnReceiveIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private MemberPointsService memberPointsService;
    @Autowired
    private MallPointsBatchRepository batchRepository;
    @Autowired
    private MallPointsLogRepository pointsLogRepository;

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

    // ---------------------------------------------------------------- 两条路径

    @Test
    @DisplayName("买家手动确认收货:按实付金额发放等额积分与成长值,并建一个批次")
    void manualReceiveGrants() {
        OrderCreateResponse order = createPaidOrder(2);
        markShipped(order.orderId());

        asClientRun(customerId, () -> orderService.confirmReceive(order.orderId()));

        assertThat(inTenant(() -> orderRepository.findById(order.orderId()).orElseThrow().getStatus()))
                .isEqualTo(MallOrder.STATUS_FINISHED);
        assertThat(pointsOf(customerId)).as("2 件 × 50.00 = 100 元 → 100 积分").isEqualTo(100);
        assertThat(growthOf(customerId)).isEqualTo(100);
        assertThat(usableBatchSum(customerId)).as("批次要与余额同步").isEqualTo(100);
        assertThat(expireDayOffset(customerId)).as("默认有效期 12 个月").isBetween(360L, 370L);
    }

    @Test
    @DisplayName("系统自动确认收货:同样发放 —— 漏掉这条路径就是'自动收货永远不给积分'")
    void autoReceiveGrantsToo() {
        OrderCreateResponse order = createPaidOrder(2);
        markShipped(order.orderId());
        forceOrderStatus(order.orderId(), MallOrder.STATUS_PENDING_RECEIVE);

        // 截止时间给到未来:等价于"这批订单全都超期了"(shipTime 已在夹具里设为过去)
        inTenant(() -> orderService.autoReceiveOrders(LocalDateTime.now().plusMinutes(1)));

        assertThat(inTenant(() -> orderRepository.findById(order.orderId()).orElseThrow().getStatus()))
                .isEqualTo(MallOrder.STATUS_FINISHED);
        assertThat(pointsOf(customerId)).as("自动确认与手动确认必须发一样多").isEqualTo(100);
        assertThat(growthOf(customerId)).isEqualTo(100);
        assertThat(usableBatchSum(customerId)).isEqualTo(100);
    }

    @Test
    @DisplayName("发放幂等:两条路径都跑一遍也只发一次")
    void grantIsIdempotentAcrossBothPaths() {
        OrderCreateResponse order = createPaidOrder(2);
        markShipped(order.orderId());

        asClientRun(customerId, () -> orderService.confirmReceive(order.orderId()));
        // 把订单挪回"待收货"再跑一次自动确认:模拟"兜底任务与手动操作撞在一起"的重复触发。
        // 不挪回去的话扫描条件根本不会命中它,这条用例就成了空跑
        forceOrderStatus(order.orderId(), MallOrder.STATUS_PENDING_RECEIVE);
        inTenant(() -> orderService.autoReceiveOrders(LocalDateTime.now().plusMinutes(1)));

        assertThat(pointsOf(customerId)).as("只发一次").isEqualTo(100);
        assertThat(usableBatchSum(customerId)).isEqualTo(100);
        assertThat(inTenant(() -> pointsLogRepository.findByCustomerIdOrderByIdDesc(customerId)))
                .as("流水也只有一条发放记录")
                .hasSize(1);
    }

    @Test
    @DisplayName("发放按实付金额向下取整:90.50 元发 90")
    void grantFloorsPayAmount() {
        inTenant(() -> {
            var sku = skuRepository.findById(skuId).orElseThrow();
            sku.setPrice(new BigDecimal("90.50"));
            skuRepository.save(sku);
            return null;
        });
        OrderCreateResponse order = createPaidOrder(1);
        markShipped(order.orderId());

        asClientRun(customerId, () -> orderService.confirmReceive(order.orderId()));

        assertThat(pointsOf(customerId)).as("90.50 元向下取整").isEqualTo(90);
        assertThat(growthOf(customerId)).isEqualTo(90);
    }

    @Test
    @DisplayName("还没发货就确认收货:被状态守卫拒绝,且不发积分")
    void rejectReceiveBeforeShip() {
        OrderCreateResponse order = createPaidOrder(2);

        assertThatThrownBy(() -> asClientRun(customerId, () -> orderService.confirmReceive(order.orderId())))
                .isInstanceOf(com.minimall.common.BusinessException.class)
                .hasMessageContaining("只有待收货的订单可以确认收货");

        assertThat(pointsOf(customerId)).as("被拒绝时不能留下任何发放痕迹").isZero();
        assertThat(growthOf(customerId)).isZero();
    }

    @Test
    @DisplayName("用积分抵现后的订单:发放基数是折后实付,不是商品总额")
    void grantBaseIsPayAmount() {
        inTenant(() -> memberPointsService.grant(customerId,
                com.minimall.infra.id.SnowflakeIdGenerator.nextId(), new BigDecimal("5000")));

        // 2 件 = 100.00 元,用 2000 积分抵 20.00 → 实付 80.00
        OrderCreateResponse order = asClient(customerId, () -> orderService.create(
                new com.minimall.mall.api.dto.CreateOrderRequest(
                        List.of(new com.minimall.mall.api.dto.CreateOrderRequest.Item(skuId, 2)),
                        addressId, null, 2000, null)));
        createdOrderIds.add(order.orderId());
        assertThat(order.payAmount()).isEqualByComparingTo("80.00");
        markShipped(order.orderId());

        asClientRun(customerId, () -> orderService.confirmReceive(order.orderId()));

        assertThat(pointsOf(customerId))
                .as("5000 - 2000 抵现 + 80 发放 = 3080;基数是实付,避免'用积分买积分'")
                .isEqualTo(3080);
    }

    // ---------------------------------------------------------------- 辅助

    /** 建一个订单(状态推到待收货由 {@link #markShipped} 负责)。 */
    private OrderCreateResponse createPaidOrder(int quantity) {
        return createOrder(customerId, addressId, quantity);
    }

    /** 置为已发货:自动确认的扫描条件是"ship_time 早于截止时间"。 */
    private void markShipped(Long orderId) {
        inTenant(() -> {
            jdbcTemplate.update("update mall_order set ship_time = ? where id = ?",
                    Timestamp.valueOf(LocalDateTime.now().minusDays(1)), orderId);
            return null;
        });
        forceOrderStatus(orderId, MallOrder.STATUS_PENDING_RECEIVE);
    }

    private int pointsOf(Long id) {
        return inTenant(() -> customerRepository.findById(id).orElseThrow().getPoints());
    }

    private int growthOf(Long id) {
        return inTenant(() -> customerRepository.findById(id).orElseThrow().getGrowthValue());
    }

    private int usableBatchSum(Long id) {
        Long sum = inTenant(() -> batchRepository.sumUsableRemain(id, TENANT_ID, LocalDateTime.now()));
        return sum == null ? 0 : sum.intValue();
    }

    /**
     * 批次过期时间距现在几天:验证字典 points_expire_months 真的被读到了。
     *
     * <p>不直接用 MONTHS.between:发放时间比断言时间早几毫秒,整月数会被算成 11。
     */
    private long expireDayOffset(Long id) {
        return inTenant(() -> {
            var batch = batchRepository.findUsable(id, TENANT_ID, LocalDateTime.now()).get(0);
            return java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), batch.getExpireTime());
        });
    }
}
