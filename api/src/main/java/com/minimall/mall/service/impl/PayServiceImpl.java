package com.minimall.mall.service.impl;

import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.infra.tenant.TenantFilterService;
import com.minimall.infra.tenant.TenantLookup;
import com.minimall.infra.tenant.TenantSnapshot;
import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderItem;
import com.minimall.mall.domain.MallOrderStatusLog;
import com.minimall.mall.domain.MallStockLog;
import com.minimall.mall.domain.MallWxPayment;
import com.minimall.mall.domain.MallWxRefund;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallOrderItemRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallOrderStatusLogRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.domain.repository.MallStockLogRepository;
import com.minimall.mall.domain.repository.MallWxPaymentRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.infra.pay.WxPayClient;
import com.minimall.mall.infra.pay.WxPayNotify;
import com.minimall.mall.infra.pay.WxPayOrderCommand;
import com.minimall.mall.service.PayService;
import com.minimall.mall.service.support.OrderAmountCalculator;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    /** 单轮查单的订单上限:余下的下一轮继续,与其它批量任务同口径。 */
    private static final int RECONCILE_BATCH_SIZE = 200;

    /** 单轮退款重投的上限。 */
    private static final int RETRY_BATCH_SIZE = 200;

    private static final String EVENT_TRANSACTION_SUCCESS = "TRANSACTION.SUCCESS";
    private static final String EVENT_REFUND_SUCCESS = "REFUND.SUCCESS";
    private static final String EVENT_REFUND_ABNORMAL = "REFUND.ABNORMAL";
    private static final String EVENT_REFUND_CLOSED = "REFUND.CLOSED";

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
    private final MallCustomerRepository customerRepository;
    private final TenantLookup tenantLookup;
    private final MallWxRefundRepository refundRepository;
    private final OrderAmountCalculator calculator;

    public PayServiceImpl(MallOrderRepository orderRepository,
                          MallOrderItemRepository orderItemRepository,
                          MallOrderStatusLogRepository statusLogRepository,
                          MallWxPaymentRepository paymentRepository,
                          MallSkuRepository skuRepository,
                          MallGoodsRepository goodsRepository,
                          MallStockLogRepository stockLogRepository,
                          WxPayClient wxPayClient,
                          TenantFilterService tenantFilterService,
                          EntityManager entityManager,
                          MallCustomerRepository customerRepository,
                          TenantLookup tenantLookup,
                          MallWxRefundRepository refundRepository,
                          OrderAmountCalculator calculator) {
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
        this.customerRepository = customerRepository;
        this.tenantLookup = tenantLookup;
        this.refundRepository = refundRepository;
        this.calculator = calculator;
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
            // 兜底:0 元订单在下单事务里已经被 settleFreeOrder 置为待发货,正常不会走到这里。
            // 留着是为了拦住"历史遗留的 0 元待支付订单",给一个比"当前状态不可支付"更好懂的提示
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

        // 2) 事务外调用统一下单(网络调用)。JSAPI 必须有 openid,所以这里要把客户取出来
        String openid = customerRepository.findById(customerId)
                .map(MallCustomer::getOpenid)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAY_CHANNEL_NOT_CONFIGURED, "缺少付款人 openid"));
        String tenantCode = tenantLookup.byId(tenantId)
                .map(TenantSnapshot::tenantCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.TENANT_ABNORMAL, "租户状态异常"));
        WxPayClient.PrepayResult result = wxPayClient
                .unifiedOrder(new WxPayOrderCommand(tenantId, tenantCode, order.getOrderNo(),
                        order.getPayAmount(), openid, "商城订单 " + order.getOrderNo()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PAY_CHANNEL_ERROR, "拉起支付失败,请稍后重试"));

        // 3) 回写预支付 ID
        payment.setPrepayId(result.prepayId());
        paymentRepository.save(payment);

        WxPayClient.PrepayResult.PayParams params = result.payParams();
        return new OrderCreateResponse.PayParams(params.timeStamp(), params.nonceStr(),
                params.packageValue(), params.signType(), params.paySign());
    }

    @Override
    public void handlePayCallback(String tenantCode, String timestamp, String nonce, String serial,
                                  String signature, String rawBody) {
        WxPayNotify notify = wxPayClient.verifyAndDecrypt(tenantCode, timestamp, nonce, serial, signature, rawBody);
        if (!EVENT_TRANSACTION_SUCCESS.equals(notify.eventType())) {
            log.info("忽略非支付成功事件 tenantCode={} eventType={}", tenantCode, notify.eventType());
            return;
        }
        String outTradeNo = text(notify.resource(), "out_trade_no");
        String transactionId = text(notify.resource(), "transaction_id");
        JsonNode amount = notify.resource().get("amount");
        BigDecimal paidAmount = calculator.toYuan(amount == null ? 0 : amount.get("total").asInt());

        // 按租户定位:流水已带租户,不需要再用超管上下文跨租户找
        Long tenantId = notify.tenantId();
        TenantContext.callAsTenant(tenantId, false, () -> {
            tenantFilterService.apply(entityManager);
            MallWxPayment payment = paymentRepository
                    .findByTenantIdAndOutTradeNo(tenantId, outTradeNo).orElse(null);
            if (payment == null) {
                log.warn("支付回调找不到对应的支付流水,已忽略 outTradeNo={}", outTradeNo);
                return null;
            }
            applyCallback(payment, outTradeNo, transactionId, paidAmount, rawBody);
            return null;
        });
    }

    @Override
    public void handleRefundCallback(String tenantCode, String timestamp, String nonce, String serial,
                                     String signature, String rawBody) {
        WxPayNotify notify = wxPayClient.verifyAndDecrypt(tenantCode, timestamp, nonce, serial, signature, rawBody);
        boolean success = EVENT_REFUND_SUCCESS.equals(notify.eventType());
        if (!success && !EVENT_REFUND_ABNORMAL.equals(notify.eventType())
                && !EVENT_REFUND_CLOSED.equals(notify.eventType())) {
            log.info("忽略非退款事件 tenantCode={} eventType={}", tenantCode, notify.eventType());
            return;
        }
        String outRefundNo = text(notify.resource(), "out_refund_no");
        String wxRefundId = text(notify.resource(), "refund_id");
        Long tenantId = notify.tenantId();

        TenantContext.callAsTenant(tenantId, false, () -> {
            tenantFilterService.apply(entityManager);
            MallWxRefund refund = refundRepository
                    .findByTenantIdAndOutRefundNo(tenantId, outRefundNo).orElse(null);
            if (refund == null) {
                log.warn("退款回调找不到对应的退款流水,已忽略 outRefundNo={}", outRefundNo);
                return null;
            }
            applyRefundCallback(refund, success, wxRefundId, rawBody, notify.eventType());
            return null;
        });
    }

    private void applyRefundCallback(MallWxRefund refund, boolean success, String wxRefundId,
                                     String rawBody, String eventType) {
        if (refund.getRefundStatus() != null && refund.getRefundStatus() == MallWxRefund.REFUND_STATUS_SUCCESS) {
            log.info("重复的退款回调,已忽略 refundId={}", refund.getId());
            return;
        }
        refund.setRefundStatus(success ? MallWxRefund.REFUND_STATUS_SUCCESS : MallWxRefund.REFUND_STATUS_FAILED);
        refund.setCallbackTime(LocalDateTime.now());
        refund.setRawCallback(rawBody);
        if (success && !wxRefundId.isEmpty()) {
            refund.setWxRefundId(wxRefundId);
        }
        if (!success) {
            // 退款失败只记录:售后单此时已经是终态,反转状态机属于另一件事,交给人工/重试处理
            log.error("退款失败,需人工处理 refundId={} outRefundNo={} eventType={}",
                    refund.getId(), refund.getOutRefundNo(), eventType);
        }
        refundRepository.save(refund);
    }

    private static String text(tools.jackson.databind.JsonNode node, String field) {
        tools.jackson.databind.JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private void applyCallback(MallWxPayment payment, String outTradeNo, String wxTransactionId,
                               BigDecimal amount, String rawBody) {
        // 幂等(3.8):已成功处理过的回调直接返回,不重复触发后续动作
        if (payment.getPayStatus() != null && payment.getPayStatus() == MallWxPayment.PAY_STATUS_SUCCESS) {
            log.info("重复的支付回调,已忽略 outTradeNo={} wxTransactionId={}", outTradeNo, wxTransactionId);
            return;
        }
        // 金额校验:回调金额与流水金额不一致说明被篡改或串单,绝不能按"已支付"处理
        if (amount != null && payment.getPayAmount() != null
                && payment.getPayAmount().compareTo(amount) != 0) {
            log.error("支付回调金额与订单金额不一致,已拒绝 outTradeNo={} 期望={} 实际={}",
                    outTradeNo, payment.getPayAmount(), amount);
            throw new BusinessException(ErrorCode.PAY_AMOUNT_INVALID, "支付金额不一致");
        }
        markPaid(payment, wxTransactionId, rawBody, "支付成功");
    }

    @Override
    public void settleFreeOrder(Long orderId) {
        MallOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getPayAmount() == null || order.getPayAmount().signum() > 0) {
            // 只有 0 元订单走这条路;非 0 元的仍由用户拉起支付
            return;
        }
        MallWxPayment payment = paymentRepository.findFirstByOrderIdOrderByIdDesc(orderId).orElse(null);
        if (payment == null) {
            log.error("0 元订单找不到支付流水,无法自动结算 orderId={}", orderId);
            return;
        }
        markPaid(payment, null, "{\"auto\":\"0 元订单,无需支付渠道\"}", "0 元订单自动支付");
    }

    @Override
    public void settleClosedPaidOrder(Long orderId, String wxTransactionId, String remark) {
        MallWxPayment payment = paymentRepository.findFirstByOrderIdOrderByIdDesc(orderId).orElse(null);
        if (payment == null) {
            log.error("查单确认已支付但找不到支付流水,无法处理 orderId={}", orderId);
            return;
        }
        markPaid(payment, wxTransactionId, "{\"source\":\"定时查单\"}", remark);
    }

    @Override
    public List<ClosedUnpaidOrder> listRecentlyClosedUnpaid(LocalDateTime closedAfter) {
        List<MallOrder> orders = orderRepository.findRecentlyClosed(MallOrder.STATUS_CANCELLED, closedAfter,
                PageRequest.of(0, RECONCILE_BATCH_SIZE));
        List<ClosedUnpaidOrder> candidates = new ArrayList<>();
        for (MallOrder order : orders) {
            MallWxPayment payment = paymentRepository.findFirstByOrderIdOrderByIdDesc(order.getId()).orElse(null);
            if (payment == null || payment.getPayStatus() == null
                    || payment.getPayStatus() != MallWxPayment.PAY_STATUS_PENDING) {
                continue;
            }
            // 没拉起过支付就不可能付过,查单是白跑一趟(而且量会大很多)
            if (payment.getPrepayId() == null || payment.getPrepayId().isEmpty()) {
                continue;
            }
            candidates.add(new ClosedUnpaidOrder(order.getTenantId(), order.getId(), payment.getOutTradeNo()));
        }
        return candidates;
    }

    @Override
    public List<RetryableRefund> listRetryableRefunds(LocalDateTime staleBefore) {
        List<MallWxRefund> rows = new ArrayList<>();
        rows.addAll(refundRepository.findByRefundStatusOrderByIdAsc(
                MallWxRefund.REFUND_STATUS_SUBMIT_FAILED, PageRequest.of(0, RETRY_BATCH_SIZE)));
        rows.addAll(refundRepository.findByRefundStatusAndWxRefundIdIsNullAndCreateTimeBeforeOrderByIdAsc(
                MallWxRefund.REFUND_STATUS_APPLYING, staleBefore, PageRequest.of(0, RETRY_BATCH_SIZE)));

        List<RetryableRefund> refunds = new ArrayList<>();
        for (MallWxRefund refund : rows) {
            refunds.add(new RetryableRefund(refund.getTenantId(), refund.getOrderId(),
                    refund.getId(), refund.getRefundAmount()));
        }
        return refunds;
    }

    /**
     * 把订单置为已支付并做后续动作:扣减库存、累计销量、写状态日志。
     *
     * <p>两个调用方:微信支付成功回调,与**0 元订单**(见 {@link #settleFreeOrder})。起点有两种:
     * 待支付、以及**已被关闭**(迟到的回调——钱收都收了,只打日志就是钱货两空,所以置回待发货)。
     * 后者的锁定库存在关单时已经退回,所以只扣实库存。
     *
     * <p>幂等:流水已是成功时直接返回,所以重复调用不会重复扣库存或重复累计销量。
     */
    private void markPaid(MallWxPayment payment, String wxTransactionId, String rawCallback, String remark) {
        if (payment.getPayStatus() != null && payment.getPayStatus() == MallWxPayment.PAY_STATUS_SUCCESS) {
            log.info("支付流水已是成功,跳过重复处理 outTradeNo={}", payment.getOutTradeNo());
            return;
        }

        payment.setPayStatus(MallWxPayment.PAY_STATUS_SUCCESS);
        payment.setWxTransactionId(wxTransactionId);
        payment.setCallbackTime(LocalDateTime.now());
        payment.setRawCallback(rawCallback);

        MallOrder order = orderRepository.findById(payment.getOrderId()).orElse(null);
        if (order == null) {
            log.error("置为已支付时找不到订单: orderId={}", payment.getOrderId());
            return;
        }
        int from = order.getStatus();
        // 订单已被超时任务关闭、或买家取消:钱却收成功了。锁定库存那时已经退回去,
        // 但钱收都收了,只打一条日志就是钱货两空 —— 所以把它置回待发货,而不是停在已取消
        boolean reopening = from == MallOrder.STATUS_CANCELLED;
        if (!reopening && from != MallOrder.STATUS_PENDING_PAY) {
            log.error("订单状态无法置为已支付: orderNo={} status={}", order.getOrderNo(), from);
            return;
        }
        order.setStatus(MallOrder.STATUS_PENDING_SHIP);
        order.setPayTime(LocalDateTime.now());

        for (MallOrderItem item : orderItemRepository.findByOrderIdOrderByIdAsc(order.getId())) {
            // 复活的订单不能再走 deductStockOnPaid:关单时 lockedStock 已经减过了,
            // 再减一次会扣到别的订单头上(见 MallSkuRepository#deductStockOnly)
            int affected = reopening
                    ? skuRepository.deductStockOnly(item.getSkuId(), order.getTenantId(), item.getQuantity())
                    : skuRepository.deductStockOnPaid(item.getSkuId(), order.getTenantId(), item.getQuantity());
            if (affected == 0) {
                log.error("{}扣减库存受影响行数为 0 orderNo={} skuId={} remark={}",
                        reopening ? "已关闭订单置为待发货" : "置为已支付",
                        order.getOrderNo(), item.getSkuId(), remark);
            }
            MallStockLog stockLog = new MallStockLog();
            stockLog.setSkuId(item.getSkuId());
            stockLog.setChangeType(STOCK_DEDUCT);
            stockLog.setChangeStock(-item.getQuantity());
            stockLog.setChangeLocked(reopening ? 0 : -item.getQuantity());
            stockLog.setBizId(order.getId());
            stockLog.setRemark(remark + "扣减库存");
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
        statusLog.setRemark(remark);
        statusLogRepository.save(statusLog);
        log.info("订单已置为已支付 orderNo={} 原因={}", order.getOrderNo(), remark);
    }

    private void increaseSaleCount(MallGoods goods, int quantity) {
        goods.setSaleCount((goods.getSaleCount() == null ? 0 : goods.getSaleCount()) + quantity);
    }
}
