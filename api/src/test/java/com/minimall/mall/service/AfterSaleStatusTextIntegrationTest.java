package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.mall.api.dto.AfterSaleApplyRequest;
import com.minimall.mall.api.dto.AfterSaleView;
import com.minimall.mall.domain.MallAfterSale;
import com.minimall.mall.domain.MallOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 售后状态文案与订单状态回退(商城设计文档 3.9)。
 *
 * <p>为什么单独盯着这两件事:
 *
 * <ol>
 *   <li><b>状态文案是买家唯一能看到的进度</b>。售后单状态有十来个,文案映射漏一个或写错一个,
 *       买家就会看到"未知"或者一个错位的阶段 —— 然后来问客服"我这单到底到哪一步了"。
 *       这段 switch 此前只有两三个分支被走到过</li>
 *   <li><b>订单状态要回退到"售后发生前的基准状态"</b>,而不是一律回到某一个值:
 *       整单退完是已取消;有收货时间就回已完成;只有发货时间就回待收货;都没发货才回待发货。
 *       回退错了买家会在订单列表里看到错误的状态(比如退完款的订单又变成"待发货")</li>
 * </ol>
 *
 * <p><b>本类没覆盖到的两条回退分支</b>:"有收货时间 → 已完成"与"只有发货时间 → 待收货"。
 * 它们的前提是**订单没有被整单退完**(整单退完一律走"已取消",优先级更高),
 * 也就是需要一张有两个明细、只退其中一条的订单。基类夹具是单明细订单,
 * 为它另造一个 SKU 的成本不低,这里如实标注而不是用一个假场景去凑。
 *
 * <p>本类自己造订单与售后单并在用例后清掉。清理顺序必须是"先售后、后订单":
 * 售后单挂着订单与明细的外键,反过来删订单会被数据库拦住。
 */
class AfterSaleStatusTextIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private AfterSaleService afterSaleService;

    /**
     * 先删售后单,再交给基类删订单与明细。
     *
     * <p>子类的 {@code @AfterEach} 会先于父类的执行(JUnit 的约定),所以这里的顺序是对的。
     * 只清本类造的订单(按夹具金额识别),不去动别的用例的数据。
     */
    @AfterEach
    void cleanAfterSalesOfFixtureOrders() {
        inTenant(() -> {
            jdbcTemplate.update("DELETE FROM mall_after_sale_log WHERE after_sale_id IN "
                    + "(SELECT a.id FROM mall_after_sale a JOIN mall_order o ON a.order_id = o.id "
                    + "WHERE o.goods_amount = 50.00)");
            jdbcTemplate.update("DELETE FROM mall_after_sale WHERE order_id IN "
                    + "(SELECT id FROM mall_order WHERE goods_amount = 50.00)");
            return null;
        });
    }

    // ================================================================ 状态文案

    @Test
    @DisplayName("售后状态文案:每个阶段都要有对应的中文,不能让买家看到未知")
    void everyStatusHasReadableText() {
        // ① 待处理 → 待买家退货(退货退款经商家同意后才进入这一步)
        Long returnRefund = applyAfterSale(prepareOrder(MallOrder.STATUS_PENDING_SHIP)[1], MallAfterSale.TYPE_RETURN_REFUND);
        assertThat(buyerView(returnRefund).statusText()).isEqualTo("待商家处理");
        asMerchant(() -> afterSaleService.approve(returnRefund, new BigDecimal("50.00")));
        assertThat(buyerView(returnRefund).statusText()).isEqualTo("待买家退货");

        // ② 待商家收货 → 商家拒绝收货
        asClientRun(customerId, () -> afterSaleService.submitReturnLogistics(returnRefund, "顺丰速运", "SF-1"));
        assertThat(buyerView(returnRefund).statusText()).isEqualTo("待商家收货");
        asMerchant(() -> afterSaleService.rejectReturn(returnRefund, "商品有损坏"));
        assertThat(buyerView(returnRefund).statusText()).isEqualTo("商家拒绝收货");

        // ③ 商家已拒绝 → 客服介入中 → 仲裁通过
        Long refundOnly = applyAfterSale(prepareOrder(MallOrder.STATUS_PENDING_SHIP)[1], MallAfterSale.TYPE_REFUND_ONLY);
        asMerchant(() -> afterSaleService.reject(refundOnly, "不符合条件"));
        assertThat(buyerView(refundOnly).statusText()).isEqualTo("商家已拒绝");
        asClientRun(customerId, () -> afterSaleService.requestArbitration(refundOnly));
        assertThat(buyerView(refundOnly).statusText()).isEqualTo("客服介入中");
        asMerchant(() -> afterSaleService.arbitrate(refundOnly, true, "支持买家"));
        assertThat(buyerView(refundOnly).statusText())
                .as("仲裁通过后钱要退给买家,文案也得说清楚").isEqualTo("仲裁通过");

        // ④ 仲裁驳回
        Long rejected = applyAfterSale(prepareOrder(MallOrder.STATUS_PENDING_SHIP)[1], MallAfterSale.TYPE_REFUND_ONLY);
        asMerchant(() -> afterSaleService.reject(rejected, "不符合条件"));
        asClientRun(customerId, () -> afterSaleService.requestArbitration(rejected));
        asMerchant(() -> afterSaleService.arbitrate(rejected, false, "商家举证充分"));
        assertThat(buyerView(rejected).statusText()).isEqualTo("仲裁驳回");

        // ⑤ 已关闭(买家撤销被拒的申请)
        Long cancelled = applyAfterSale(prepareOrder(MallOrder.STATUS_PENDING_SHIP)[1], MallAfterSale.TYPE_REFUND_ONLY);
        asMerchant(() -> afterSaleService.reject(cancelled, "不符合条件"));
        asClientRun(customerId, () -> afterSaleService.cancelByBuyer(cancelled));
        assertThat(buyerView(cancelled).statusText()).isEqualTo("已关闭");

        // ⑥ 未知状态:防御分支。状态是库里的枚举值,理论上不会出现别的;
        //    但真出现时宁可显示"未知"也不能让买家页整块报错 —— 这一条就用改库来模拟
        inTenant(() -> {
            jdbcTemplate.update("UPDATE mall_after_sale SET status = 99 WHERE id = ?", cancelled);
            return null;
        });
        assertThat(buyerView(cancelled).statusText()).as("无法识别的状态要给一个兜底文案").isEqualTo("未知");
    }

    // ================================================================ 状态守卫

    @Test
    @DisplayName("商家动作的状态守卫:拒绝只对待处理生效,拒绝收货只对待商家收货生效")
    void merchantActionsAreGuardedByStatus() {
        Long afterSaleId = applyAfterSale(prepareOrder(MallOrder.STATUS_PENDING_SHIP)[1], MallAfterSale.TYPE_RETURN_REFUND);

        // 待处理时"拒绝收货"没有意义(货都还没寄回来)
        assertThatThrownBy(() -> asMerchant(() -> afterSaleService.rejectReturn(afterSaleId, "理由")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有待确认收货的售后单可以拒绝收货");

        // 同意之后进入待买家退货,此时不能再"拒绝"(那是待处理阶段才能做的决定)
        asMerchant(() -> afterSaleService.approve(afterSaleId, new BigDecimal("50.00")));
        assertThatThrownBy(() -> asMerchant(() -> afterSaleService.reject(afterSaleId, "理由")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有待处理的售后单可以拒绝");
    }

    // ================================================================ 订单状态回退

    @Test
    @DisplayName("售后完成后订单回退:整单退完转已取消;退货退款走完全程同样如此")
    void orderStatusRestoresAfterAfterSale() {
        // ① 待发货:仅退款走完(整单唯一明细已全退)→ 已取消
        long[] pending = prepareOrder(MallOrder.STATUS_PENDING_SHIP);
        Long pendingAfterSale = applyAfterSale(pending[1], MallAfterSale.TYPE_REFUND_ONLY);
        assertThat(orderStatus(pending[0])).as("申请后先进入售后中").isEqualTo(MallOrder.STATUS_AFTER_SALE);
        asMerchant(() -> afterSaleService.approve(pendingAfterSale, new BigDecimal("50.00")));
        assertThat(orderStatus(pending[0])).as("整单退完订单转已取消").isEqualTo(MallOrder.STATUS_CANCELLED);

        // ② 待收货:退货退款走完整条退货流程 → 同样是整单退完,转已取消
        long[] shipped = prepareOrder(MallOrder.STATUS_PENDING_RECEIVE);
        Long shippedAfterSale = applyAfterSale(shipped[1], MallAfterSale.TYPE_RETURN_REFUND);
        asMerchant(() -> afterSaleService.approve(shippedAfterSale, new BigDecimal("50.00")));
        asClientRun(customerId, () -> afterSaleService.submitReturnLogistics(shippedAfterSale, "顺丰速运", "SF-2"));
        assertThat(buyerView(shippedAfterSale).statusText()).isEqualTo("待商家收货");
        asMerchant(() -> afterSaleService.confirmReturnReceived(shippedAfterSale, null, null, null));
        assertThat(orderStatus(shipped[0]))
                .as("单明细订单退完就是整单退完 —— 无论之前是待发货还是待收货")
                .isEqualTo(MallOrder.STATUS_CANCELLED);
    }

    // ---------------------------------------------------------------- 夹具

    /**
     * 造一个处于指定状态的订单,返回 {@code [订单 id, 第一条明细 id]}。
     *
     * <p>两个 id 都要用:申请售后要**明细 id**,断言订单状态要**订单 id** ——
     * 混用过一次,表现是 findById 查不到、报一个和"售后"毫无关系的空 Optional。
     */
    private long[] prepareOrder(int orderStatus) {
        Long orderId = createOrder(customerId, addressId, 1).orderId();
        forceOrderStatus(orderId, orderStatus);
        Long itemId = inTenant(() -> orderItemRepository.findByOrderIdOrderByIdAsc(orderId).get(0).getId());
        return new long[]{orderId, itemId};
    }

    private Long applyAfterSale(Long orderItemId, int afterSaleType) {
        return asClient(customerId, () -> afterSaleService.apply(new AfterSaleApplyRequest(
                orderItemId, afterSaleType, "端到端用例", null, new BigDecimal("50.00"), null)));
    }

    private AfterSaleView buyerView(Long afterSaleId) {
        return asClient(customerId, () -> afterSaleService.detailForBuyer(afterSaleId));
    }

    private int orderStatus(Long orderId) {
        return inTenant(() -> orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    /** 商家侧动作:服务层不校验权限,只需要租户上下文。 */
    private void asMerchant(Runnable action) {
        inTenant(() -> {
            action.run();
            return null;
        });
    }
}
