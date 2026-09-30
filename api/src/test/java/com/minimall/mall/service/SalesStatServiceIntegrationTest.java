package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.mall.api.dto.CreateOrderRequest;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.api.dto.SalesStatReport;
import com.minimall.mall.api.dto.TopGoodsView;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.MallWxRefund;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
import com.minimall.mall.service.support.WxPayCallbackFixture;
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
 * 销售统计(商城设计文档 3.4)。
 *
 * <p>统计最容易出的错是**口径**:把没付钱的订单也算进销售额(数字虚高,而且看起来特别"好"),
 * 或者区间端点少算一天。这两类错都不会报错,只会让人拿着错的数字做决定。
 *
 * <p>本类的订单都**回拨到一个固定过去时刻**再统计:同一个库上还跑着别的用例,
 * 它们留下的已支付订单会落在"最近"窗口里,按绝对条数断言必然误伤。
 */
class SalesStatServiceIntegrationTest extends MallClientServiceTestBase {

    /** 只有本用例的订单会落进这个时刻(其它用例的订单都是"刚刚"创建的)。 */
    private final LocalDateTime anchor = LocalDateTime.now().minusDays(45).withNano(0);

    @Autowired
    private SalesStatService salesStatService;
    @Autowired
    private WxPayCallbackFixture payCallback;
    @Autowired
    private MallWxRefundRepository refundRepository;

    /** 本类额外造的商品与 SKU(排行走在用例需要):基类只清它自己那一个。 */
    private final List<Long> extraGoodsIds = new ArrayList<>();
    private final List<Long> extraSkuIds = new ArrayList<>();

    @AfterEach
    void cleanExtraGoodsRefunds() {
        inTenant(() -> {
            refundRepository.findAll().stream()
                    .filter(refund -> refund.getOutRefundNo() != null && refund.getOutRefundNo().startsWith("SR"))
                    .forEach(refundRepository::delete);
            for (Long sku : extraSkuIds) {
                skuRepository.findById(sku).ifPresent(skuRepository::delete);
            }
            for (Long goods : extraGoodsIds) {
                goodsRepository.findById(goods).ifPresent(goodsRepository::delete);
            }
            extraSkuIds.clear();
            extraGoodsIds.clear();
            return null;
        });
    }

    @Test
    @DisplayName("只统计已支付的订单:同一区间里的未支付订单不算")
    void countsOnlyPaidOrders() {
        OrderCreateResponse unpaid = orderAt(skuId, 1, anchor);
        OrderCreateResponse paid = paidOrderAt(skuId, 2, anchor);

        SalesStatReport report = inTenant(() -> salesStatService.report(anchor, anchor));

        assertThat(report.summary().orderCount()).as("未支付订单不该计入").isEqualTo(1);
        assertThat(report.summary().paidAmount()).isEqualByComparingTo(paid.payAmount());
        assertThat(report.summary().avgOrderAmount()).isEqualByComparingTo(paid.payAmount());
        assertThat(report.summary().netAmount())
                .as("没有退款时净额等于销售额").isEqualByComparingTo(paid.payAmount());
        assertThat(unpaid.orderId()).isNotEqualTo(paid.orderId());
    }

    @Test
    @DisplayName("区间两端都含,区间外的一笔都不算")
    void rangeIsInclusiveAndExcludesOutside() {
        LocalDateTime earlier = anchor.minusHours(2);
        OrderCreateResponse old = paidOrderAt(skuId, 1, earlier);
        OrderCreateResponse recent = paidOrderAt(skuId, 1, anchor);

        SalesStatReport exact = inTenant(() -> salesStatService.report(earlier, earlier));
        assertThat(exact.summary().orderCount()).as("起止取同一时刻,只有含边界才算得到").isEqualTo(1);
        assertThat(exact.summary().paidAmount()).isEqualByComparingTo(old.payAmount());

        SalesStatReport span = inTenant(() -> salesStatService.report(earlier, anchor));
        assertThat(span.summary().orderCount()).as("两端都含,两笔都要在").isEqualTo(2);
        assertThat(span.summary().paidAmount())
                .isEqualByComparingTo(old.payAmount().add(recent.payAmount()));

        SalesStatReport outside = inTenant(() -> salesStatService.report(earlier.minusHours(1), earlier.minusMinutes(30)));
        assertThat(outside.summary().orderCount()).isZero();
    }

    @Test
    @DisplayName("退款成功额按退款时间统计:区间外的那笔不算")
    void subtractsSucceededRefund() {
        OrderCreateResponse paid = paidOrderAt(skuId, 2, anchor);
        saveSucceededRefund(paid.orderId(), new BigDecimal("20.00"), anchor);
        // 区间外的一笔:不按退款时间过滤的话,这个数字会混进来
        saveSucceededRefund(paid.orderId(), new BigDecimal("30.00"), anchor.minusDays(1));

        SalesStatReport report = inTenant(() -> salesStatService.report(anchor, anchor));

        assertThat(report.summary().refundAmount()).isEqualByComparingTo("20.00");
        assertThat(report.summary().netAmount())
                .isEqualByComparingTo(paid.payAmount().subtract(new BigDecimal("20.00")));
        assertThat(report.summary().orderCount()).as("退款不该把订单从销售额里摘掉").isEqualTo(1);
    }

    @Test
    @DisplayName("商品排行:按下单金额降序,带数量与快照名")
    void ranksGoodsByAmount() {
        paidOrderAt(skuId, 3, anchor);                     // 50.00 × 3 = 150.00
        Long bigSkuId = newGoods("排行走在用例商品", new BigDecimal("500.00"), 10);
        Long bigGoodsId = inTenant(() -> skuRepository.findById(bigSkuId).orElseThrow().getGoodsId());
        paidOrderAt(bigSkuId, 2, anchor);                  // 500.00 × 2 = 1000.00

        SalesStatReport report = inTenant(() -> salesStatService.report(anchor, anchor));

        TopGoodsView bigRow = topRowOf(report, bigGoodsId);
        assertThat(bigRow.goodsName()).isEqualTo("排行走在用例商品");
        assertThat(bigRow.quantity()).isEqualTo(2);
        assertThat(bigRow.amount()).isEqualByComparingTo("1000.00");

        TopGoodsView smallRow = topRowOf(report, goodsId);
        assertThat(smallRow.quantity()).isEqualTo(3);
        assertThat(smallRow.amount()).isEqualByComparingTo("150.00");

        List<Long> rankedIds = report.topGoods().stream().map(TopGoodsView::goodsId).toList();
        assertThat(rankedIds.indexOf(bigGoodsId)).as("金额大的商品排在前面")
                .isLessThan(rankedIds.indexOf(goodsId));
    }

    @Test
    @DisplayName("区间内没有订单时给 0 而不是空白")
    void emptyRangeShowsZero() {
        LocalDateTime from = LocalDateTime.now().minusDays(200).withNano(0);
        LocalDateTime to = from.plusMinutes(1);

        SalesStatReport report = inTenant(() -> salesStatService.report(from, to));

        assertThat(report.summary().orderCount()).isZero();
        assertThat(report.summary().paidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.summary().refundAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.summary().netAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.summary().avgOrderAmount()).as("没有订单时客单价给 0,不能除零")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.topGoods()).isEmpty();
    }

    @Test
    @DisplayName("开始时间晚于结束时间要报错,而不是返回一份空报告")
    void rejectsReversedRange() {
        LocalDateTime now = LocalDateTime.now();
        assertThatThrownBy(() -> inTenant(() -> salesStatService.report(now, now.minusDays(1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("开始时间不能晚于结束时间");
    }

    // ——— 辅助 ———

    /** 下单并把下单时间回拨到 {@code createTime}(业务代码不允许改这一列,只能直接改库)。 */
    private OrderCreateResponse orderAt(Long targetSkuId, int quantity, LocalDateTime createTime) {
        OrderCreateResponse order = asClient(customerId, () -> orderService.create(new CreateOrderRequest(
                List.of(new CreateOrderRequest.Item(targetSkuId, quantity)), addressId, null, null, null)));
        jdbcTemplate.update("update mall_order set create_time = ? where id = ?",
                Timestamp.valueOf(createTime), order.orderId());
        return order;
    }

    private OrderCreateResponse paidOrderAt(Long targetSkuId, int quantity, LocalDateTime createTime) {
        OrderCreateResponse order = orderAt(targetSkuId, quantity, createTime);
        inTenant(() -> {
            payCallback.paySuccess(order.orderNo(), order.payAmount());
            return null;
        });
        return order;
    }

    /** 从排行里取某个商品的明细行(商品 ID 是排行的聚合键)。 */
    private TopGoodsView topRowOf(SalesStatReport report, Long targetGoodsId) {
        return report.topGoods().stream()
                .filter(row -> targetGoodsId.equals(row.goodsId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("商品 " + targetGoodsId + " 不在排行里"));
    }

    /** 直接落一条"退款成功"流水:走完整退款流程要造售后单,而这里要验的是统计口径。 */
    private void saveSucceededRefund(Long orderId, BigDecimal amount, LocalDateTime callbackTime) {
        inTenant(() -> {
            MallWxRefund refund = new MallWxRefund();
            refund.setOrderId(orderId);
            refund.setAfterSaleId(0L);
            refund.setOutRefundNo("SR" + System.nanoTime());
            refund.setRefundAmount(amount);
            refund.setRefundStatus(MallWxRefund.REFUND_STATUS_SUCCESS);
            refund.setCallbackTime(callbackTime);
            refundRepository.save(refund);
            return null;
        });
    }

    private Long newGoods(String name, BigDecimal price, int stock) {
        return inTenant(() -> {
            MallGoods goods = new MallGoods();
            goods.setCategoryId(0L);
            goods.setGoodsName(name);
            goods.setMainImage("https://example.com/rank.png");
            goods.setSalePriceMin(price);
            goods.setSalePriceMax(price);
            goods.setTotalStock(stock);
            goods.setSaleCount(0);
            goods.setStatus(1);
            goods.setSortOrder(0);
            Long goodsId = goodsRepository.save(goods).getId();
            extraGoodsIds.add(goodsId);

            MallSku sku = new MallSku();
            sku.setGoodsId(goodsId);
            sku.setSkuCode("IT-RANK-" + System.nanoTime());
            sku.setSkuName("默认规格");
            sku.setPrice(price);
            sku.setStock(stock);
            sku.setLockedStock(0);
            sku.setStatus(1);
            Long skuId = skuRepository.save(sku).getId();
            extraSkuIds.add(skuId);
            return skuId;
        });
    }
}
