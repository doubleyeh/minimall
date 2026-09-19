package com.minimall.mall.service.impl;

import com.minimall.api.mall.dto.OrderCreateResponse;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.infra.tenant.TenantFilterService;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderItem;
import com.minimall.mall.domain.MallOrderStatusLog;
import com.minimall.mall.domain.MallStockLog;
import com.minimall.mall.domain.MallWxPayment;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallOrderItemRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallOrderStatusLogRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.domain.repository.MallStockLogRepository;
import com.minimall.mall.domain.repository.MallWxPaymentRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.infra.pay.WxPayClient;
import com.minimall.mall.service.PayService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 支付实现(商城设计文档 3.3、3.8)。
 *
 * <p><b>两类方法的边界是刻意划开的</b>:
 * <ul>
 *   <li>{@link #prepay} 标注 {@code NOT_SUPPORTED}:**统一下单是外部网络调用**,
 *       跑在事务里会让数据库连接一起等网络,高峰期连接池会被拖干(3.3 的括注)。
 *       它内部用 repository 的各自小事务完成读写</li>
 *   <li>{@link #handlePayCallback} 是**本地事务**:回调要更新支付流水、订单、库存、销量,
 *       这些必须整体成功或整体回滚</li>
 * </ul>
 */
@Service
@Transactional
public class PayServiceImpl implements PayService {

    private static final Logger log = LoggerFactory.getLogger(PayServiceImpl.class);

    private static final int STOCK_DEDUCT = 3;

    private final MallOrderRepository orderRepository;
    private final MallOrderItemRepository orderItemRepository;
    private final MallOrderStatusLogRepository statusLogRepository;
    private final MallWxPaymentRepository paymentRepository;
    private final MallSkuRepository skuRepository;
    private final MallGoodsRepository goodsRepository;
    private final MallStockLogRepository stockLogRepository;
    private final WxPayClient wxPayClient;
    private final TenantFilterService tenantFilterService;
    private final EntityManager entityManager;

    public PayServiceImpl(MallOrderRepository orderRepository,
                          MallOrderItemRepository orderItemRepository,
                          MallOrderStatusLogRepository statusLogRepository,
                          MallWxPaymentRepository paymentRepository,
                          MallSkuRepository skuRepository,
                          MallGoodsRepository goodsRepository,
                          MallStockLogRepository stockLogRepository,
                          WxPayClient wxPayClient,
                          TenantFilterService tenantFilterService,
                          EntityManager entityManager) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.statusLogRepository = statusLogRepository;
        this.paymentRepository = paymentRepository;
        this.skuRepository = skuRepository;
        this.goodsRepository = goodsRepository;
        this.stockLogRepository = stockLogRepository;
        this.wxPayClient = wxPayClient;
        this.tenantFilterService = tenantFilterService;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OrderCreateResponse.PayParams prepay(Long orderId) {
        Long customerId = ClientContext.requireCustomerId();
        Long tenantId = TenantContext.getTenantId();
        MallOrder order = orderRepository.findById(orderId)
                .filter(item -> Objects.equals(item.getCustomerId(), customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在"));
        if (order.getStatus() != MallOrder.STATUS_PENDING_PAY) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "订单当前状态不可支付");
        }
        if (order.getPayAmount() == null || order.getPayAmount().signum() <= 0) {
            // 0 元订单不需要走支付渠道(全额优惠),前端直接进"待发货"
            throw new BusinessException(ErrorCode.PARAM_INVALID, "订单无需支付");
        }

        // 1) 先落支付流水(待支付),这样即使后面统一下单失败,也有据可查
        MallWxPayment payment = paymentRepository.findFirstByOrderIdOrderByIdDesc(orderId).orElse(null);
        if (payment == null || payment.getPayStatus() == null
                || payment.getPayStatus() != MallWxPayment.PAY_STATUS_PENDING) {
            payment = new MallWxPayment();
            payment.setOrderId(orderId);
            payment.setOutTradeNo(order.getOrderNo());
            payment.setPayAmount(order.getPayAmount());
            payment.setPayStatus(MallWxPayment.PAY_STATUS_PENDING);
            payment = paymentRepository.save(payment);
        }
        if (payment.getPrepayId() != null) {
            log.info("订单已有预支付单,直接复用 orderNo={}", order.getOrderNo());
        }

        // 2) 事务外调用统一下单(网络调用)
        WxPayClient.PrepayResult result = wxPayClient
                .unifiedOrder(order.getOrderNo(), order.getPayAmount(), null, "商城订单 " + order.getOrderNo())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_ERROR, "拉起支付失败,请稍后重试"));

        // 3) 回写预支付 ID
        payment.setPrepayId(result.prepayId());
        paymentRepository.save(payment);

        WxPayClient.PrepayResult.PayParams params = result.payParams();
        return new OrderCreateResponse.PayParams(params.timeStamp(), params.nonceStr(),
                params.packageValue(), params.signType(), params.paySign());
    }

    @Override
    public void handlePayCallback(String outTradeNo, String wxTransactionId, BigDecimal amount, boolean success,
                                  String rawBody) {
        // 回调没有租户上下文:先切到超管上下文(两个过滤器都不启用)按商户订单号定位支付流水。
        // 订单号全局唯一(见 OrderNumberGenerator),所以这次定位不会跨租户串单
        MallWxPayment located = TenantContext.callAsTenant(null, true, () -> {
            tenantFilterService.apply(entityManager);
            return paymentRepository.findByOutTradeNo(outTradeNo).orElse(null);
        });
        if (located == null) {
            log.warn("支付回调找不到对应的支付流水,已忽略 outTradeNo={}", outTradeNo);
            return;
        }
        Long tenantId = located.getTenantId();
        if (tenantId == null) {
            log.warn("支付流水缺少租户信息,已忽略 outTradeNo={}", outTradeNo);
            return;
        }
        // 切回该租户继续处理:后续所有读写都在正确租户下执行
        TenantContext.callAsTenant(tenantId, false, () -> {
            tenantFilterService.apply(entityManager);
            applyCallback(located, outTradeNo, wxTransactionId, amount, success, rawBody);
            return null;
        });
    }

    private void applyCallback(MallWxPayment payment, String outTradeNo, String wxTransactionId,
                               BigDecimal amount, boolean success, String rawBody) {
        // 幂等(3.8):已成功处理过的回调直接返回,不重复触发后续动作
        if (payment.getPayStatus() != null && payment.getPayStatus() == MallWxPayment.PAY_STATUS_SUCCESS) {
            log.info("重复的支付回调,已忽略 outTradeNo={} wxTransactionId={}", outTradeNo, wxTransactionId);
            return;
        }
        if (!success) {
            // 支付失败/关闭:只改支付流水状态,不动订单 —— 订单由超时任务或用户取消驱动(3.8)
            payment.setPayStatus(MallWxPayment.PAY_STATUS_FAILED);
            payment.setCallbackTime(LocalDateTime.now());
            payment.setRawCallback(rawBody);
            return;
        }
        // 金额校验:回调金额与流水金额不一致说明被篡改或串单,绝不能按"已支付"处理
        if (amount != null && payment.getPayAmount() != null
                && payment.getPayAmount().compareTo(amount) != 0) {
            log.error("支付回调金额与订单金额不一致,已拒绝 outTradeNo={} 期望={} 实际={}",
                    outTradeNo, payment.getPayAmount(), amount);
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "支付金额不一致");
        }

        payment.setPayStatus(MallWxPayment.PAY_STATUS_SUCCESS);
        payment.setWxTransactionId(wxTransactionId);
        payment.setCallbackTime(LocalDateTime.now());
        payment.setRawCallback(rawBody);

        MallOrder order = orderRepository.findById(payment.getOrderId()).orElse(null);
        if (order == null) {
            log.error("支付回调找不到订单: orderId={}", payment.getOrderId());
            return;
        }
        if (order.getStatus() != MallOrder.STATUS_PENDING_PAY) {
            // 订单可能已被超时任务关闭:支付成功了但货已释放,这是"需要退款"的场景,
            // 先记录,交给售后退款流程处理(不在这里静默改订单状态)
            log.error("订单状态不是待支付,回调无法置为已支付: orderNo={} status={}",
                    order.getOrderNo(), order.getStatus());
            return;
        }
        int from = order.getStatus();
        order.setStatus(MallOrder.STATUS_PENDING_SHIP);
        order.setPayTime(LocalDateTime.now());

        for (MallOrderItem item : orderItemRepository.findByOrderIdOrderByIdAsc(order.getId())) {
            int affected = skuRepository.deductStockOnPaid(item.getSkuId(), order.getTenantId(), item.getQuantity());
            if (affected == 0) {
                log.warn("支付回调扣减库存受影响行数为 0(可能已处理) orderNo={} skuId={}",
                        order.getOrderNo(), item.getSkuId());
            }
            MallStockLog stockLog = new MallStockLog();
            stockLog.setSkuId(item.getSkuId());
            stockLog.setChangeType(STOCK_DEDUCT);
            stockLog.setChangeStock(-item.getQuantity());
            stockLog.setChangeLocked(-item.getQuantity());
            stockLog.setBizId(order.getId());
            stockLog.setRemark("支付成功扣减库存");
            stockLogRepository.save(stockLog);
            // 销量(3.8 最后一步):支付成功才计数,下单未支付不该算销量。
            // 按数量累加而不是 +1 —— 一次买 3 件就是 3 件销量
            goodsRepository.findById(item.getGoodsId())
                    .ifPresent(goods -> increaseSaleCount(goods, item.getQuantity()));
        }

        MallOrderStatusLog statusLog = new MallOrderStatusLog();
        statusLog.setOrderId(order.getId());
        statusLog.setFromStatus(from);
        statusLog.setToStatus(MallOrder.STATUS_PENDING_SHIP);
        statusLog.setOperatorType(MallOrderStatusLog.OPERATOR_SYSTEM);
        statusLog.setRemark("支付成功");
        statusLogRepository.save(statusLog);
        log.info("订单支付成功 orderNo={} wxTransactionId={}", order.getOrderNo(), wxTransactionId);
    }

    private void increaseSaleCount(MallGoods goods, int quantity) {
        goods.setSaleCount((goods.getSaleCount() == null ? 0 : goods.getSaleCount()) + quantity);
    }
}
