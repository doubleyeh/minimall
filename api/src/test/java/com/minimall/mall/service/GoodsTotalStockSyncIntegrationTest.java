package com.minimall.mall.service;

import com.minimall.mall.api.dto.AfterSaleApplyRequest;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.domain.MallAfterSale;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallAfterSaleImageRepository;
import com.minimall.mall.domain.repository.MallAfterSaleLogRepository;
import com.minimall.mall.domain.repository.MallAfterSaleRepository;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
import com.minimall.mall.service.support.WxPayCallbackFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code mall_goods.total_stock} 与 SKU 库存的同步(见 MallGoods 类注释)。
 *
 * <p>这个冗余汇总一旦漂移,管理端"总库存"就会长期对不上盘 —— 而它不影响交易,
 * 所以不会有任何报错,只能靠人盘货时发现。因此每条会改 SKU {@code stock} 的路径
 * (支付扣减、取消/售后回库、复活实扣)都要在这里钉一下。
 */
class GoodsTotalStockSyncIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private WxPayCallbackFixture payCallback;
    @Autowired
    private OrderAdminService orderAdminService;
    @Autowired
    private AfterSaleService afterSaleService;
    @Autowired
    private MallAfterSaleRepository afterSaleRepository;
    @Autowired
    private MallAfterSaleLogRepository afterSaleLogRepository;
    @Autowired
    private MallAfterSaleImageRepository afterSaleImageRepository;
    @Autowired
    private MallWxRefundRepository refundRepository;

    @AfterEach
    void cleanAfterSales() {
        // 售后单引用订单,必须赶在基类删订单之前清掉
        inTenant(() -> {
            for (MallAfterSale sale : afterSaleRepository.findAll().stream()
                    .filter(row -> customerId.equals(row.getCustomerId()) || otherCustomerId.equals(row.getCustomerId()))
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
            return null;
        });
    }

    /** 基类夹具:商品与 SKU 都是 10 件。 */
    private int totalStock() {
        return inTenant(() -> goodsRepository.findById(goodsId).orElseThrow().getTotalStock());
    }

    private MallSku sku() {
        return inTenant(() -> skuRepository.findById(skuId).orElseThrow());
    }

    private OrderCreateResponse paidOrder(int quantity) {
        OrderCreateResponse order = createOrder(customerId, addressId, quantity);
        inTenant(() -> {
            payCallback.paySuccess(order.orderNo(), order.payAmount());
            return null;
        });
        return order;
    }

    @Test
    @DisplayName("下单锁定与买家取消:只动 locked,总库存不跟着变")
    void lockingDoesNotChangeTotalStock() {
        OrderCreateResponse order = createOrder(customerId, addressId, 2);

        assertThat(totalStock()).as("锁定不算库存减少,汇总只统计 stock").isEqualTo(10);
        assertThat(sku().getLockedStock()).isEqualTo(2);

        asClientRun(customerId, () -> orderService.cancel(order.orderId()));

        assertThat(totalStock()).as("释放锁定同样不该改汇总").isEqualTo(10);
        assertThat(sku().getStock()).isEqualTo(10);
        assertThat(sku().getLockedStock()).as("锁定退回去了").isZero();
    }

    @Test
    @DisplayName("支付实扣与商家取消回库:总库存跟着走")
    void paidDeductAndMerchantCancelRestoreTotalStock() {
        OrderCreateResponse order = paidOrder(2);
        assertThat(totalStock()).as("支付把 stock 从 10 扣到 8").isEqualTo(8);

        inTenant(() -> {
            orderAdminService.cancel(order.orderId(), "缺货");
            return null;
        });

        assertThat(totalStock()).as("回库后汇总要跟着涨回去").isEqualTo(10);
    }

    @Test
    @DisplayName("超时关闭后钱又到账:复活走实扣,总库存也要跟着减")
    void reopeningDeductsTotalStock() {
        OrderCreateResponse order = createOrder(customerId, addressId, 1);
        asClientRun(customerId, () -> orderService.cancel(order.orderId()));
        assertThat(totalStock()).as("取消时只退锁定,汇总不动").isEqualTo(10);

        inTenant(() -> {
            payCallback.paySuccess(order.orderNo(), order.payAmount());
            return null;
        });

        assertThat(totalStock()).as("复活要实扣一件").isEqualTo(9);
    }

    @Test
    @DisplayName("售后退货回库:总库存涨回去")
    void afterSaleRestoreUpdatesTotalStock() {
        OrderCreateResponse order = paidOrder(1);
        assertThat(totalStock()).isEqualTo(9);
        Long itemId = inTenant(() -> orderItemRepository.findByOrderIdOrderByIdAsc(order.orderId()).get(0).getId());

        Long afterSaleId = asClient(customerId, () -> afterSaleService.apply(new AfterSaleApplyRequest(
                itemId, MallAfterSale.TYPE_RETURN_REFUND, "不想要了", "包装未拆", new BigDecimal("1.00"), null)));
        inTenant(() -> {
            afterSaleService.approve(afterSaleId, null);
            return null;
        });
        asClientRun(customerId, () -> afterSaleService.submitReturnLogistics(afterSaleId, "圆通", "YT1"));
        inTenant(() -> {
            afterSaleService.confirmReturnReceived(afterSaleId, null, null, null);
            return null;
        });

        assertThat(totalStock()).as("退货回库后汇总要涨回去").isEqualTo(10);
    }
}
