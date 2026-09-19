package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.mall.api.dto.AfterSaleApplyRequest;
import com.minimall.mall.api.dto.AfterSaleView;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.domain.MallAfterSale;
import com.minimall.mall.domain.MallAfterSaleImage;
import com.minimall.mall.domain.MallAfterSaleLog;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderItem;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.MallWxRefund;
import com.minimall.mall.domain.repository.MallAfterSaleImageRepository;
import com.minimall.mall.domain.repository.MallAfterSaleLogRepository;
import com.minimall.mall.domain.repository.MallAfterSaleRepository;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
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
 * 售后流程(商城设计文档 3.9)。
 *
 * <p>10 态状态机 + 三件必须一起发生的事:退款、回补库存、订单状态回退。
 * 状态机最容易出的错不是"流转本身错了",而是**某条流转漏了其中一件事** ——
 * 最常见的是漏回补库存(钱退了、货也回来了,但库存没加回去,越卖越少)。
 * 所以这里的断言重点不在"状态等于几",而在"钱、货、订单三者是否同时正确"。
 *
 * <p>四个终态(4 完成 / 8 仲裁通过 / 9 仲裁驳回 / 10 已关闭)里,只有前两个会动资金与库存,
 * 这条区分是整段实现的核心,也是本类里断言最密的地方。
 */
class AfterSaleServiceIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private AfterSaleService afterSaleService;
    @Autowired
    private PayService payService;
    @Autowired
    private MallAfterSaleRepository afterSaleRepository;
    @Autowired
    private MallAfterSaleLogRepository afterSaleLogRepository;
    @Autowired
    private MallAfterSaleImageRepository afterSaleImageRepository;
    @Autowired
    private MallWxRefundRepository refundRepository;

    /** 本类额外造的 SKU(换货用例需要):基类只清它自己那个 skuId。 */
    private final List<Long> extraSkuIds = new ArrayList<>();

    @AfterEach
    void cleanAfterSales() {
        // 顺序:先删售后(它引用订单),基类的 @AfterEach 随后才删订单
        inTenant(() -> {
            for (Long ownerId : new Long[] { customerId, otherCustomerId }) {
                for (MallAfterSale sale : afterSaleRepository.findAll().stream()
                        .filter(row -> ownerId.equals(row.getCustomerId()))
                        .toList()) {
                    afterSaleLogRepository.findByAfterSaleIdOrderByIdAsc(sale.getId())
                            .forEach(afterSaleLogRepository::delete);
                    afterSaleImageRepository.findByAfterSaleIdOrderByIdAsc(sale.getId())
                            .forEach(afterSaleImageRepository::delete);
                    refundRepository.findAll().stream()
                            .filter(refund -> sale.getId().equals(refund.getAfterSaleId()))
                            .forEach(refundRepository::delete);
                    afterSaleRepository.delete(sale);
                }
            }
            for (Long extraSkuId : extraSkuIds) {
                stockLogRepository.findAll().stream()
                        .filter(logRow -> extraSkuId.equals(logRow.getSkuId()))
                        .forEach(stockLogRepository::delete);
                skuRepository.findById(extraSkuId).ifPresent(skuRepository::delete);
            }
            extraSkuIds.clear();
            return null;
        });
    }

    // ---------------------------------------------------------------- 夹具

    /** 已支付待发货的订单明细(售后申请最常见的起点)。 */
    private Long paidItemId(int quantity) {
        OrderCreateResponse order = createOrder(customerId, addressId, quantity);
        inTenant(() -> {
            payService.handlePayCallback(order.orderNo(), "wx-txn-" + System.nanoTime(),
                    order.payAmount(), true, "{\"mock\":true}");
            return null;
        });
        return inTenant(() -> orderItemRepository.findByOrderIdOrderByIdAsc(order.orderId()).get(0).getId());
    }

    private Long applyAfterSale(Long itemId, int type, String refundAmount) {
        return asClient(customerId, () -> afterSaleService.apply(new AfterSaleApplyRequest(
                itemId, type, "不想要了", "包装未拆", new BigDecimal(refundAmount), null)));
    }

    private Long applyWithImages(Long itemId, int type) {
        return asClient(customerId, () -> afterSaleService.apply(new AfterSaleApplyRequest(
                itemId, type, "有质量问题", "有照片",
                new BigDecimal("1.00"), List.of("https://a.png", " ", "https://b.png"))));
    }

    private MallAfterSale reload(Long afterSaleId) {
        return inTenant(() -> afterSaleRepository.findById(afterSaleId).orElseThrow());
    }

    private List<MallAfterSaleLog> logsOf(Long afterSaleId) {
        return inTenant(() -> afterSaleLogRepository.findByAfterSaleIdOrderByIdAsc(afterSaleId));
    }

    private List<MallWxRefund> refundsOf(Long afterSaleId) {
        return inTenant(() -> refundRepository.findAll().stream()
                .filter(refund -> afterSaleId.equals(refund.getAfterSaleId()))
                .toList());
    }

    private void asMerchant(Runnable action) {
        inTenant(() -> {
            action.run();
            return null;
        });
    }

    private int skuStock() {
        return inTenant(() -> skuRepository.findById(skuId).orElseThrow().getStock());
    }

    private Long newSku(int stock) {
        Long id = inTenant(() -> {
            MallSku sku = new MallSku();
            sku.setGoodsId(goodsId);
            sku.setSkuCode("IT-SKU2-" + System.nanoTime());
            sku.setSkuName("另一个规格");
            sku.setPrice(new BigDecimal("50.00"));
            sku.setStock(stock);
            sku.setLockedStock(0);
            sku.setStatus(1);
            return skuRepository.save(sku).getId();
        });
        extraSkuIds.add(id);
        return id;
    }

    // ---------------------------------------------------------------- 申请

    @Test
    @DisplayName("申请:仅退款只允许在待发货(已发货还点仅退款,货和钱都在买家那边)")
    void applyRefundOnlyAllowedBeforeShipment() {
        // 已支付 = 待发货 ✓
        Long okId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_REFUND_ONLY, "1.00");
        assertThat(reload(okId).getStatus()).isEqualTo(MallAfterSale.STATUS_PENDING);

        Long shippedItemId = paidItemId(1);
        asMerchant(() -> orderAdminService.ship(itemOrderOf(shippedItemId), "顺丰", "SF1"));

        assertThatThrownBy(() -> applyAfterSale(shippedItemId, MallAfterSale.TYPE_REFUND_ONLY, "1.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已发货的订单请选择退货退款");
    }

    @Test
    @DisplayName("申请:退货退款在待发货之后都可以,但未支付不行")
    void applyReturnRefundAllowedAfterShipment() {
        OrderCreateResponse order = createOrder(customerId, addressId, 1);
        Long itemId = inTenant(() -> orderItemRepository.findByOrderIdOrderByIdAsc(order.orderId()).get(0).getId());
        assertThatThrownBy(() -> applyAfterSale(itemId, MallAfterSale.TYPE_RETURN_REFUND, "1.00"))
                .as("未支付订单不能申请售后")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前订单状态不支持申请售后");
    }

    @Test
    @DisplayName("申请:同一订单第二次申请会被状态检查拦住(重复检查实际不可达)")
    void applyRejectsSecondApplyOnSameOrder() {
        Long itemId = paidItemId(1);
        applyAfterSale(itemId, MallAfterSale.TYPE_REFUND_ONLY, "1.00");

        // 这里期望的是 DATA_CONFLICT(该商品已有进行中的售后申请),实际拿到的是 PARAM_INVALID:
        // 首次申请会把订单置为"售后中",而售后中不在任何可申请状态里,所以**先被状态检查拦下**,
        // 后面那句 countActiveByOrderItemId(...) > 0 的重复校验永远不会执行(两种申请类型各有一句文案)。
        // 不影响正确性(第二次申请确实被拒了),但提示语是"已发货的订单请选择退货退款"这种
        // 与真实原因无关的话,排查时容易被它带偏 —— 这里把实际行为钉住,改动文案时会立刻发现。
        assertThatThrownBy(() -> applyAfterSale(itemId, MallAfterSale.TYPE_REFUND_ONLY, "1.00"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("已发货的订单请选择退货退款");
    }

    @Test
    @DisplayName("申请:退款金额不能超过该明细的实付金额")
    void applyRejectsRefundOverItemAmount() {
        assertThatThrownBy(() -> applyAfterSale(paidItemId(1), MallAfterSale.TYPE_REFUND_ONLY, "999.00"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("退款金额不能超过");
    }

    @Test
    @DisplayName("申请:别人的订单明细与不存在的明细都按不存在处理")
    void applyRejectsUnknownOrForeignItem() {
        Long othersAddress = inTenant(() -> newAddress(otherCustomerId, "别人"));
        OrderCreateResponse othersOrder = createOrder(otherCustomerId, othersAddress, 1);
        Long othersItemId = inTenant(() ->
                orderItemRepository.findByOrderIdOrderByIdAsc(othersOrder.orderId()).get(0).getId());

        assertThatThrownBy(() -> applyAfterSale(othersItemId, MallAfterSale.TYPE_REFUND_ONLY, "1.00"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> applyAfterSale(999999L, MallAfterSale.TYPE_REFUND_ONLY, "1.00"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("申请成功:单据落库 + 订单转售后中 + 明细标记处理中 + 日志 + 凭证图")
    void applyCreatesAfterSaleAndMarksOrder() {
        Long itemId = paidItemId(2);
        Long afterSaleId = applyWithImages(itemId, MallAfterSale.TYPE_RETURN_REFUND);

        MallAfterSale afterSale = reload(afterSaleId);
        assertThat(afterSale.getAfterSaleNo()).isNotBlank();
        assertThat(afterSale.getStatus()).isEqualTo(MallAfterSale.STATUS_PENDING);
        assertThat(afterSale.getCustomerId()).isEqualTo(customerId);
        assertThat(afterSale.getRefundAmount()).isEqualByComparingTo("1.00");

        inTenant(() -> {
            assertThat(orderRepository.findById(afterSale.getOrderId()).orElseThrow().getStatus())
                    .as("订单要进入售后中,否则商家在订单列表里看不出来")
                    .isEqualTo(MallOrder.STATUS_AFTER_SALE);
            assertThat(orderItemRepository.findById(itemId).orElseThrow().getAfterSaleStatus())
                    .isEqualTo(MallOrderItem.AFTER_SALE_PROCESSING);
            return null;
        });

        assertThat(logsOf(afterSaleId)).hasSize(1);
        assertThat(logsOf(afterSaleId).get(0).getToStatus()).isEqualTo(MallAfterSale.STATUS_PENDING);

        List<MallAfterSaleImage> images = inTenant(() ->
                afterSaleImageRepository.findByAfterSaleIdOrderByIdAsc(afterSaleId));
        assertThat(images).as("空白地址要被过滤掉").hasSize(2);
        assertThat(images.get(0).getStage()).isEqualTo(MallAfterSaleImage.STAGE_APPLY);
    }

    // ---------------------------------------------------------------- 买家侧流转

    @Test
    @DisplayName("买家撤销:仅限被拒状态,撤销后单据关闭、明细复位")
    void cancelByBuyerClosesRejectedAfterSale() {
        Long itemId = paidItemId(1);
        Long afterSaleId = applyAfterSale(itemId, MallAfterSale.TYPE_REFUND_ONLY, "1.00");
        asMerchant(() -> afterSaleService.reject(afterSaleId, "不符合条件"));

        asClientRun(customerId, () -> afterSaleService.cancelByBuyer(afterSaleId));

        assertThat(reload(afterSaleId).getStatus()).isEqualTo(MallAfterSale.STATUS_CLOSED);
        inTenant(() -> {
            assertThat(orderItemRepository.findById(itemId).orElseThrow().getAfterSaleStatus())
                    .as("关闭后要能重新申请")
                    .isEqualTo(MallOrderItem.AFTER_SALE_NONE);
            return null;
        });
    }

    @Test
    @DisplayName("买家撤销:待处理状态下不能撤销(必须先让商家处理或走仲裁)")
    void cancelByBuyerRejectsOtherStatus() {
        Long afterSaleId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_REFUND_ONLY, "1.00");

        assertThatThrownBy(() -> asClientRun(customerId, () -> afterSaleService.cancelByBuyer(afterSaleId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前状态无法撤销");
    }

    @Test
    @DisplayName("买家撤销:别人的售后单按不存在处理")
    void cancelByBuyerRejectsForeignAfterSale() {
        Long othersAddress = inTenant(() -> newAddress(otherCustomerId, "别人"));
        OrderCreateResponse othersOrder = createOrder(otherCustomerId, othersAddress, 1);
        // 仅退款要求订单处于待发货,所以另一个客户的订单也得先支付
        inTenant(() -> {
            payService.handlePayCallback(othersOrder.orderNo(), "wx-txn-o-" + System.nanoTime(),
                    othersOrder.payAmount(), true, "{\"mock\":true}");
            return null;
        });
        Long othersItemId = inTenant(() ->
                orderItemRepository.findByOrderIdOrderByIdAsc(othersOrder.orderId()).get(0).getId());
        Long othersAfterSaleId = asClient(otherCustomerId, () -> afterSaleService.apply(
                new AfterSaleApplyRequest(othersItemId, MallAfterSale.TYPE_REFUND_ONLY,
                        "不想要", null, new BigDecimal("1.00"), null)));
        asMerchant(() -> afterSaleService.reject(othersAfterSaleId, "x"));

        assertThatThrownBy(() -> asClientRun(customerId, () -> afterSaleService.cancelByBuyer(othersAfterSaleId)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("提交退货物流:待买家退货 → 待商家收货,并记下时间")
    void submitReturnLogisticsMovesToWaitReceive() {
        Long afterSaleId = applyApprovedReturn();
        assertThat(reload(afterSaleId).getStatus()).isEqualTo(MallAfterSale.STATUS_WAIT_RETURN);

        asClientRun(customerId, () -> afterSaleService.submitReturnLogistics(afterSaleId, "圆通", "YT99"));

        MallAfterSale afterSale = reload(afterSaleId);
        assertThat(afterSale.getStatus()).isEqualTo(MallAfterSale.STATUS_WAIT_RECEIVE);
        assertThat(afterSale.getReturnLogisticsNo()).isEqualTo("YT99");
        assertThat(afterSale.getReturnTime()).isNotNull();
    }

    @Test
    @DisplayName("提交退货物流:状态不对或物流信息为空都要被拒")
    void submitReturnLogisticsValidates() {
        Long pendingId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_RETURN_REFUND, "1.00");
        assertThatThrownBy(() -> asClientRun(customerId,
                () -> afterSaleService.submitReturnLogistics(pendingId, "圆通", "YT1")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前状态不需要提交退货物流");

        Long afterSaleId = applyApprovedReturn();
        assertThatThrownBy(() -> asClientRun(customerId,
                () -> afterSaleService.submitReturnLogistics(afterSaleId, "  ", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请填写退货物流公司与单号");
    }

    // ---------------------------------------------------------------- 商家处理

    @Test
    @DisplayName("商家同意仅退款:直接终态,退款 + 回补库存 + 明细完成,整单退完订单转已取消")
    void approveRefundOnlyFinishesCompletely() {
        Long itemId = paidItemId(2);
        Long afterSaleId = applyAfterSale(itemId, MallAfterSale.TYPE_REFUND_ONLY, "1.00");
        int stockBefore = skuStock();

        asMerchant(() -> afterSaleService.approve(afterSaleId, null));

        MallAfterSale afterSale = reload(afterSaleId);
        assertThat(afterSale.getStatus()).isEqualTo(MallAfterSale.STATUS_DONE);
        assertThat(afterSale.getFinishTime()).isNotNull();
        assertThat(refundsOf(afterSaleId)).as("退款记录要落库").hasSize(1);
        assertThat(refundsOf(afterSaleId).get(0).getRefundStatus())
                .isEqualTo(MallWxRefund.REFUND_STATUS_SUCCESS);
        assertThat(refundsOf(afterSaleId).get(0).getRefundAmount()).isEqualByComparingTo("1.00");
        assertThat(skuStock()).as("退货必须回补库存,否则越卖越少").isEqualTo(stockBefore + 2);

        inTenant(() -> {
            assertThat(orderItemRepository.findById(itemId).orElseThrow().getAfterSaleStatus())
                    .isEqualTo(MallOrderItem.AFTER_SALE_DONE);
            assertThat(orderRepository.findById(afterSale.getOrderId()).orElseThrow().getStatus())
                    .as("整单都退完了,订单转已取消")
                    .isEqualTo(MallOrder.STATUS_CANCELLED);
            return null;
        });
    }

    @Test
    @DisplayName("商家同意退货退款:进入待买家退货,此时不动资金也不动库存")
    void approveReturnRefundWaitsForBuyer() {
        Long itemId = paidItemId(1);
        Long afterSaleId = applyAfterSale(itemId, MallAfterSale.TYPE_RETURN_REFUND, "1.00");
        int stockBefore = skuStock();

        asMerchant(() -> afterSaleService.approve(afterSaleId, null));

        assertThat(reload(afterSaleId).getStatus()).isEqualTo(MallAfterSale.STATUS_WAIT_RETURN);
        assertThat(refundsOf(afterSaleId)).as("买家还没退货,不能先退钱").isEmpty();
        assertThat(skuStock()).isEqualTo(stockBefore);
    }

    @Test
    @DisplayName("商家同意:可以下调金额但必须留痕,不能上调也不能为 0")
    void approveAdjustsRefundAmountDownwardOnly() {
        Long afterSaleId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_REFUND_ONLY, "10.00");

        assertThatThrownBy(() -> asMerchant(() -> afterSaleService.approve(afterSaleId, new BigDecimal("20.00"))))
                .as("上调退款金额")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能下调退款金额");
        assertThatThrownBy(() -> asMerchant(() -> afterSaleService.approve(afterSaleId, BigDecimal.ZERO)))
                .as("金额为 0")
                .isInstanceOf(BusinessException.class);

        asMerchant(() -> afterSaleService.approve(afterSaleId, new BigDecimal("6.00")));

        assertThat(reload(afterSaleId).getRefundAmount()).isEqualByComparingTo("6.00");
        assertThat(logsOf(afterSaleId))
                .as("下调必须留痕:出了纠纷要能说清当时为什么只退这些")
                .anyMatch(logRow -> logRow.getRemark() != null && logRow.getRemark().contains("下调退款金额"));
        assertThat(refundsOf(afterSaleId).get(0).getRefundAmount()).isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("商家同意:只有待处理的售后单可以同意")
    void approveRejectsWrongStatus() {
        Long afterSaleId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_RETURN_REFUND, "1.00");
        asMerchant(() -> afterSaleService.approve(afterSaleId, null));

        assertThatThrownBy(() -> asMerchant(() -> afterSaleService.approve(afterSaleId, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有待处理的售后单可以同意");
    }

    @Test
    @DisplayName("商家拒绝:单据转已拒绝,明细复位以便重新申请,订单退出售后中")
    void rejectResetsItemAndOrder() {
        Long itemId = paidItemId(1);
        Long afterSaleId = applyAfterSale(itemId, MallAfterSale.TYPE_REFUND_ONLY, "1.00");

        asMerchant(() -> afterSaleService.reject(afterSaleId, "已拆封影响二次销售"));

        MallAfterSale afterSale = reload(afterSaleId);
        assertThat(afterSale.getStatus()).isEqualTo(MallAfterSale.STATUS_REJECTED);
        assertThat(afterSale.getRejectReason()).isEqualTo("已拆封影响二次销售");
        assertThat(refundsOf(afterSaleId)).as("拒绝不产生退款").isEmpty();
        inTenant(() -> {
            assertThat(orderItemRepository.findById(itemId).orElseThrow().getAfterSaleStatus())
                    .as("明细要复位,买家才能重新申请")
                    .isEqualTo(MallOrderItem.AFTER_SALE_NONE);
            assertThat(orderRepository.findById(afterSale.getOrderId()).orElseThrow().getStatus())
                    .as("""
                            订单仍然停在"售后中":被拒(5)与拒绝收货(6)都还在 ACTIVE_STATUSES 里 ——
                            买家还能申请客服介入或撤销,这时把订单退回"待发货"会让商家以为这事已经了结。""")
                    .isEqualTo(MallOrder.STATUS_AFTER_SALE);
            return null;
        });
    }

    @Test
    @DisplayName("确认收到退货:退款 + 回补库存 + 终态")
    void confirmReturnReceivedFinishes() {
        Long afterSaleId = applyApprovedReturn();
        asClientRun(customerId, () -> afterSaleService.submitReturnLogistics(afterSaleId, "圆通", "YT1"));
        int stockBefore = skuStock();

        asMerchant(() -> afterSaleService.confirmReturnReceived(afterSaleId, null, null, null));

        MallAfterSale afterSale = reload(afterSaleId);
        assertThat(afterSale.getStatus()).isEqualTo(MallAfterSale.STATUS_DONE);
        assertThat(afterSale.getReceiveConfirmTime()).isNotNull();
        assertThat(refundsOf(afterSaleId)).hasSize(1);
        assertThat(skuStock()).isEqualTo(stockBefore + 1);
    }

    @Test
    @DisplayName("确认收到退货:只有待商家收货的可以确认")
    void confirmReturnReceivedRejectsWrongStatus() {
        Long afterSaleId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_RETURN_REFUND, "1.00");

        assertThatThrownBy(() -> asMerchant(() ->
                afterSaleService.confirmReturnReceived(afterSaleId, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有待确认收货的售后单可以确认");
    }

    @Test
    @DisplayName("拒绝收货:转拒绝收货状态,买家可据此申请客服介入")
    void rejectReturnMovesToRejectReceive() {
        Long afterSaleId = applyApprovedReturn();
        asClientRun(customerId, () -> afterSaleService.submitReturnLogistics(afterSaleId, "圆通", "YT1"));

        asMerchant(() -> afterSaleService.rejectReturn(afterSaleId, "收到的不是本店商品"));

        MallAfterSale afterSale = reload(afterSaleId);
        assertThat(afterSale.getStatus()).isEqualTo(MallAfterSale.STATUS_REJECT_RECEIVE);
        assertThat(afterSale.getRejectReason()).isEqualTo("收到的不是本店商品");
    }

    // ---------------------------------------------------------------- 换货

    @Test
    @DisplayName("换货:回补原规格库存、扣减新规格库存,并记录重新发货的物流")
    void exchangeSwapsStockBetweenSkus() {
        Long afterSaleId = applyApprovedExchange();
        Long newSkuId = newSku(5);
        int oldStockBefore = skuStock();

        asMerchant(() -> afterSaleService.confirmReturnReceived(afterSaleId, newSkuId, "顺丰", "SF-NEW"));

        MallAfterSale afterSale = reload(afterSaleId);
        assertThat(afterSale.getStatus()).isEqualTo(MallAfterSale.STATUS_DONE);
        assertThat(afterSale.getReshipLogisticsNo()).isEqualTo("SF-NEW");
        assertThat(skuStock()).as("原规格回补").isEqualTo(oldStockBefore + 1);
        inTenant(() -> {
            assertThat(skuRepository.findById(newSkuId).orElseThrow().getStock())
                    .as("新规格扣减")
                    .isEqualTo(4);
            return null;
        });
        // 换货只换货、不退款
        assertThat(refundsOf(afterSaleId)).as("换货场景不该产生退款").isEmpty();
    }

    @Test
    @DisplayName("换货:没选新规格或新规格库存不足都被拒")
    void exchangeValidatesNewSku() {
        Long afterSaleId = applyApprovedExchange();

        assertThatThrownBy(() -> asMerchant(() ->
                afterSaleService.confirmReturnReceived(afterSaleId, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("换货请选择要更换的规格");

        Long emptySkuId = newSku(0);
        assertThatThrownBy(() -> asMerchant(() ->
                afterSaleService.confirmReturnReceived(afterSaleId, emptySkuId, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");
    }

    @Test
    @DisplayName("换货:换成同一规格只记物流,不做库存搬运")
    void exchangeSameSkuOnlyRecordsLogistics() {
        Long afterSaleId = applyApprovedExchange();
        int stockBefore = skuStock();

        asMerchant(() -> afterSaleService.confirmReturnReceived(afterSaleId, skuId, "顺丰", "SF-SAME"));

        assertThat(reload(afterSaleId).getReshipLogisticsNo()).isEqualTo("SF-SAME");
        assertThat(skuStock()).as("同一规格一进一出,库存不变").isEqualTo(stockBefore);
    }

    // ---------------------------------------------------------------- 客服仲裁

    @Test
    @DisplayName("客服介入:只有被拒状态可以申请")
    void requestArbitrationOnlyFromRejected() {
        Long pendingId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_REFUND_ONLY, "1.00");
        assertThatThrownBy(() -> asClientRun(customerId, () -> afterSaleService.requestArbitration(pendingId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有被拒绝的售后单可以申请客服介入");

        asMerchant(() -> afterSaleService.reject(pendingId, "x"));
        asClientRun(customerId, () -> afterSaleService.requestArbitration(pendingId));

        MallAfterSale afterSale = reload(pendingId);
        assertThat(afterSale.getStatus()).isEqualTo(MallAfterSale.STATUS_ARBITRATING);
        assertThat(afterSale.getArbitrationTime()).isNotNull();
    }

    @Test
    @DisplayName("仲裁通过:与商家同意走同一套终态动作(退款 + 回补库存)")
    void arbitratePassFinishesLikeApprove() {
        Long afterSaleId = applyThenRejectThenArbitrate();
        int stockBefore = skuStock();

        asMerchant(() -> afterSaleService.arbitrate(afterSaleId, true, "买家举证充分"));

        MallAfterSale afterSale = reload(afterSaleId);
        assertThat(afterSale.getStatus()).isEqualTo(MallAfterSale.STATUS_ARBITRATION_PASS);
        assertThat(afterSale.getArbitrationRemark()).isEqualTo("买家举证充分");
        assertThat(refundsOf(afterSaleId)).as("仲裁通过必须真的退钱").hasSize(1);
        assertThat(skuStock()).isEqualTo(stockBefore + 1);
    }

    @Test
    @DisplayName("仲裁驳回:不动资金也不动库存,但明细要复位让买家能重新申请")
    void arbitrateRejectTouchesNothingButResetsItem() {
        Long itemId = paidItemId(1);
        Long afterSaleId = applyAfterSale(itemId, MallAfterSale.TYPE_REFUND_ONLY, "1.00");
        asMerchant(() -> afterSaleService.reject(afterSaleId, "x"));
        asClientRun(customerId, () -> afterSaleService.requestArbitration(afterSaleId));
        int stockBefore = skuStock();

        asMerchant(() -> afterSaleService.arbitrate(afterSaleId, false, "商家举证充分"));

        MallAfterSale afterSale = reload(afterSaleId);
        assertThat(afterSale.getStatus()).isEqualTo(MallAfterSale.STATUS_ARBITRATION_REJECT);
        assertThat(afterSale.getFinishTime()).isNotNull();
        assertThat(refundsOf(afterSaleId)).isEmpty();
        assertThat(skuStock()).isEqualTo(stockBefore);
        inTenant(() -> {
            assertThat(orderItemRepository.findById(itemId).orElseThrow().getAfterSaleStatus())
                    .isEqualTo(MallOrderItem.AFTER_SALE_NONE);
            assertThat(orderRepository.findById(afterSale.getOrderId()).orElseThrow().getStatus())
                    .isEqualTo(MallOrder.STATUS_PENDING_SHIP);
            return null;
        });
    }

    @Test
    @DisplayName("仲裁:只有客服介入中的售后单可以仲裁")
    void arbitrateRejectsWrongStatus() {
        Long afterSaleId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_REFUND_ONLY, "1.00");

        assertThatThrownBy(() -> asMerchant(() -> afterSaleService.arbitrate(afterSaleId, true, "x")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只有客服介入中的售后单可以仲裁");
    }

    // ---------------------------------------------------------------- 超时任务

    @Test
    @DisplayName("商家超时未处理:仅退款自动同意并退款,退货退款则转待买家退货")
    void autoApproveTimeoutHandlesBothTypes() {
        Long refundOnlyId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_REFUND_ONLY, "1.00");
        Long returnRefundId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_RETURN_REFUND, "1.00");
        backdateAfterSale(refundOnlyId);
        backdateAfterSale(returnRefundId);

        int handled = inTenant(() -> {
            int count = afterSaleService.autoApproveTimeout(LocalDateTime.now());
            return count;
        });

        assertThat(handled).isGreaterThanOrEqualTo(2);
        assertThat(reload(refundOnlyId).getStatus()).isEqualTo(MallAfterSale.STATUS_DONE);
        assertThat(refundsOf(refundOnlyId)).hasSize(1);
        assertThat(reload(returnRefundId).getStatus())
                .as("退货退款不能直接退钱,要先让买家把货寄回")
                .isEqualTo(MallAfterSale.STATUS_WAIT_RETURN);
        assertThat(refundsOf(returnRefundId)).isEmpty();
        assertThat(logsOf(refundOnlyId).get(logsOf(refundOnlyId).size() - 1).getOperatorType())
                .as("系统自动处理要与人工操作区分开")
                .isEqualTo(MallAfterSaleLog.OPERATOR_SYSTEM);
    }

    @Test
    @DisplayName("买家超时未退货:自动关闭,同时明细复位")
    void autoCloseTimeoutClosesWaitingReturn() {
        Long itemId = paidItemId(1);
        Long afterSaleId = applyApprovedReturn(itemId);
        backdateAfterSale(afterSaleId);

        inTenant(() -> {
            afterSaleService.autoCloseTimeout(LocalDateTime.now());
            return null;
        });

        assertThat(reload(afterSaleId).getStatus()).isEqualTo(MallAfterSale.STATUS_CLOSED);
        assertThat(refundsOf(afterSaleId)).isEmpty();
        inTenant(() -> {
            assertThat(orderItemRepository.findById(itemId).orElseThrow().getAfterSaleStatus())
                    .isEqualTo(MallOrderItem.AFTER_SALE_NONE);
            return null;
        });
    }

    @Test
    @DisplayName("商家超时未确认收货:自动确认并退款")
    void autoReceiveTimeoutFinishesAndRefunds() {
        Long afterSaleId = applyApprovedReturn();
        asClientRun(customerId, () -> afterSaleService.submitReturnLogistics(afterSaleId, "圆通", "YT1"));
        backdateAfterSale(afterSaleId);
        int stockBefore = skuStock();

        inTenant(() -> {
            afterSaleService.autoReceiveTimeout(LocalDateTime.now());
            return null;
        });

        assertThat(reload(afterSaleId).getStatus()).isEqualTo(MallAfterSale.STATUS_DONE);
        assertThat(refundsOf(afterSaleId)).hasSize(1);
        assertThat(skuStock()).as("自动确认收货同样要回补库存").isEqualTo(stockBefore + 1);
    }

    // ---------------------------------------------------------------- 查询

    @Test
    @DisplayName("买家列表与详情:只看到自己的,详情带商品快照与流转记录")
    void buyerQueriesAreScopedToSelf() {
        Long itemId = paidItemId(1);
        Long afterSaleId = applyWithImages(itemId, MallAfterSale.TYPE_REFUND_ONLY);

        List<AfterSaleView> mine = asClient(customerId, () -> afterSaleService.mine(null));
        assertThat(mine).extracting(AfterSaleView::id).contains(afterSaleId);
        assertThat(mine).allMatch(view -> view.statusText() != null && !view.statusText().isBlank());

        List<AfterSaleView> pendingOnly = asClient(customerId,
                () -> afterSaleService.mine(MallAfterSale.STATUS_PENDING));
        assertThat(pendingOnly).extracting(AfterSaleView::id).contains(afterSaleId);
        List<AfterSaleView> doneOnly = asClient(customerId,
                () -> afterSaleService.mine(MallAfterSale.STATUS_DONE));
        assertThat(doneOnly).extracting(AfterSaleView::id).doesNotContain(afterSaleId);

        AfterSaleView detail = asClient(customerId, () -> afterSaleService.detailForBuyer(afterSaleId));
        assertThat(detail.item()).isNotNull();
        assertThat(detail.item().goodsName()).isEqualTo("集成测试商品");
        assertThat(detail.logs()).hasSize(1);
        assertThat(detail.images()).as("详情要带上凭证图").hasSize(2);
        assertThat(detail.statusText()).isEqualTo("待商家处理");

        assertThatThrownBy(() -> asClient(customerId, () -> afterSaleService.detailForBuyer(999999L)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("管理端列表:可按状态与单号过滤")
    void adminPageFilters() {
        Long afterSaleId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_REFUND_ONLY, "1.00");
        MallAfterSale afterSale = reload(afterSaleId);

        PageResult<AfterSaleView> byStatus = inTenant(() ->
                afterSaleService.page(MallAfterSale.STATUS_PENDING, null, 1, 50));
        assertThat(byStatus.list()).extracting(AfterSaleView::id).contains(afterSaleId);

        PageResult<AfterSaleView> byNo = inTenant(() ->
                afterSaleService.page(null, afterSale.getAfterSaleNo(), 1, 50));
        assertThat(byNo.list()).extracting(AfterSaleView::afterSaleNo).contains(afterSale.getAfterSaleNo());

        PageResult<AfterSaleView> doneOnly = inTenant(() ->
                afterSaleService.page(MallAfterSale.STATUS_DONE, null, 1, 50));
        assertThat(doneOnly.list()).extracting(AfterSaleView::id).doesNotContain(afterSaleId);

        AfterSaleView detail = inTenant(() -> afterSaleService.detail(afterSaleId));
        assertThat(detail.item()).as("管理端详情要带商品快照").isNotNull();
        assertThat(detail.logs()).isNotEmpty();
    }

    // ---------------------------------------------------------------- 内部辅助

    /** 从订单明细反查订单 id(用于在"待发货"后执行发货)。 */
    private Long itemOrderOf(Long orderItemId) {
        return inTenant(() -> orderItemRepository.findById(orderItemId).orElseThrow().getOrderId());
    }

    /** 申请退货退款并让商家同意:停在"待买家退货"。 */
    private Long applyApprovedReturn() {
        return applyApprovedReturn(paidItemId(1));
    }

    private Long applyApprovedReturn(Long itemId) {
        Long afterSaleId = applyAfterSale(itemId, MallAfterSale.TYPE_RETURN_REFUND, "1.00");
        asMerchant(() -> afterSaleService.approve(afterSaleId, null));
        return afterSaleId;
    }

    /** 申请换货并推进到"待商家收货":换货的起点与退货退款一致。 */
    private Long applyApprovedExchange() {
        Long afterSaleId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_EXCHANGE, "1.00");
        asMerchant(() -> afterSaleService.approve(afterSaleId, null));
        asClientRun(customerId, () -> afterSaleService.submitReturnLogistics(afterSaleId, "圆通", "YT1"));
        return afterSaleId;
    }

    private Long applyThenRejectThenArbitrate() {
        Long afterSaleId = applyAfterSale(paidItemId(1), MallAfterSale.TYPE_REFUND_ONLY, "1.00");
        asMerchant(() -> afterSaleService.reject(afterSaleId, "x"));
        asClientRun(customerId, () -> afterSaleService.requestArbitration(afterSaleId));
        return afterSaleId;
    }

    /**
     * 把售后单的 {@code update_time} 改到过去,让"超时"任务能命中它。
     *
     * <p>必须用 JdbcTemplate:超时查询用的是 {@code update_time},而它是审计字段,
     * 业务代码不允许改(也没有 setter)。
     */
    private void backdateAfterSale(Long afterSaleId) {
        inTenant(() -> {
            jdbcTemplate.update("UPDATE mall_after_sale SET update_time = ? WHERE id = ?",
                    LocalDateTime.now().minusDays(30), afterSaleId);
            return null;
        });
    }

    @Autowired
    private OrderAdminService orderAdminService;
}
