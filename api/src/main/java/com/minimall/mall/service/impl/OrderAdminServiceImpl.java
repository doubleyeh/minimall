package com.minimall.mall.service.impl;

import com.minimall.mall.api.dto.AdminOrderView;
import com.minimall.mall.api.dto.ClientOrderView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderItem;
import com.minimall.mall.domain.MallOrderStatusLog;
import com.minimall.mall.domain.MallStockLog;
import com.minimall.mall.domain.MallWxRefund;
import com.minimall.mall.domain.QMallOrder;
import com.minimall.mall.domain.repository.MallOrderItemRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallOrderStatusLogRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.domain.repository.MallStockLogRepository;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
import com.minimall.mall.infra.OrderNumberGenerator;
import com.minimall.mall.infra.pay.WxPayClient;
import com.minimall.mall.service.OrderAdminService;
import com.querydsl.core.BooleanBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商家管理端订单实现(商城设计文档 3.4)。
 *
 * <p>商家取消已支付订单时,**回补库存与创建退款记录必须在同一事务内**:只回补库存不退款是吞钱,
 * 只退款不回补库存是白送一件货。
 */
@Service
@Transactional
public class OrderAdminServiceImpl implements OrderAdminService {

    private static final Logger log = LoggerFactory.getLogger(OrderAdminServiceImpl.class);

    /** 回库(退款场景)。 */
    private static final int STOCK_RESTORE = 4;

    private final MallOrderRepository orderRepository;
    private final MallOrderItemRepository orderItemRepository;
    private final MallOrderStatusLogRepository statusLogRepository;
    private final MallSkuRepository skuRepository;
    private final MallStockLogRepository stockLogRepository;
    private final MallWxRefundRepository refundRepository;
    private final OrderNumberGenerator numberGenerator;
    private final WxPayClient wxPayClient;

    public OrderAdminServiceImpl(MallOrderRepository orderRepository,
                                 MallOrderItemRepository orderItemRepository,
                                 MallOrderStatusLogRepository statusLogRepository,
                                 MallSkuRepository skuRepository,
                                 MallStockLogRepository stockLogRepository,
                                 MallWxRefundRepository refundRepository,
                                 OrderNumberGenerator numberGenerator,
                                 WxPayClient wxPayClient) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.statusLogRepository = statusLogRepository;
        this.skuRepository = skuRepository;
        this.stockLogRepository = stockLogRepository;
        this.refundRepository = refundRepository;
        this.numberGenerator = numberGenerator;
        this.wxPayClient = wxPayClient;
    }

    @Override
    public PageResult<AdminOrderView> page(String orderNo, Integer status, int pageNo, int pageSize) {
        QMallOrder qOrder = QMallOrder.mallOrder;
        BooleanBuilder where = new BooleanBuilder();
        if (orderNo != null && !orderNo.isBlank()) {
            where.and(qOrder.orderNo.contains(orderNo));
        }
        if (status != null) {
            where.and(qOrder.status.eq(status));
        }
        // 按创建时间倒序:商家最先关心的是最新订单
        Page<MallOrder> page = orderRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1))
                        .withSort(org.springframework.data.domain.Sort.by("id").descending()));
        List<AdminOrderView> views = page.getContent().stream()
                .map(order -> toView(order, null))
                .toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public AdminOrderView detail(Long orderId) {
        MallOrder order = load(orderId);
        return toView(order, orderItemRepository.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(this::toItemView).toList());
    }

    @Override
    public void ship(Long orderId, String logisticsCompany, String logisticsNo) {
        if (logisticsCompany == null || logisticsCompany.isBlank()
                || logisticsNo == null || logisticsNo.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请填写物流公司与物流单号");
        }
        MallOrder order = load(orderId);
        if (order.getStatus() != MallOrder.STATUS_PENDING_SHIP) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只有待发货的订单可以发货");
        }
        int from = order.getStatus();
        order.setLogisticsCompany(logisticsCompany);
        order.setLogisticsNo(logisticsNo);
        order.setShipTime(LocalDateTime.now());
        order.setStatus(MallOrder.STATUS_PENDING_RECEIVE);
        writeStatusLog(order.getId(), from, MallOrder.STATUS_PENDING_RECEIVE,
                MallOrderStatusLog.OPERATOR_MERCHANT, currentUserId(), "商家发货:" + logisticsCompany + " " + logisticsNo);
    }

    @Override
    public void cancel(Long orderId, String reason) {
        MallOrder order = load(orderId);
        if (order.getStatus() != MallOrder.STATUS_PENDING_SHIP) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只有待发货的订单可以由商家取消");
        }
        int from = order.getStatus();
        Long tenantId = order.getTenantId();

        // 先改实体,再做批量更新(理由见 OrderServiceImpl#releaseOrderResources 的注释)
        order.setStatus(MallOrder.STATUS_CANCELLED);
        order.setCloseReason(3);
        order.setCancelTime(LocalDateTime.now());

        for (MallOrderItem item : orderItemRepository.findByOrderIdOrderByIdAsc(orderId)) {
            // 已支付订单的库存是"实扣"过的,取消要把货放回去(与未支付取消只解锁定的语义不同)
            skuRepository.restoreStock(item.getSkuId(), tenantId, item.getQuantity());
            MallStockLog stockLog = new MallStockLog();
            stockLog.setSkuId(item.getSkuId());
            stockLog.setChangeType(STOCK_RESTORE);
            stockLog.setChangeStock(item.getQuantity());
            stockLog.setChangeLocked(0);
            stockLog.setBizId(orderId);
            stockLog.setRemark("商家取消订单回库");
            stockLogRepository.save(stockLog);
        }

        // 创建退款记录并发起退款(3.4:商家取消未发货订单,触发全额退款流程)
        createRefund(order, order.getPayAmount(), null);

        writeStatusLog(orderId, from, MallOrder.STATUS_CANCELLED, MallOrderStatusLog.OPERATOR_MERCHANT,
                currentUserId(), "商家取消:" + (reason == null ? "未填写原因" : reason));
    }

    /**
     * 创建退款记录并发起退款。
     *
     * <p>真实微信退款是异步的:这里只保证"退款申请"落库({@code refundStatus = 0}),
     * 最终状态由退款回调更新 —— 与支付回调同一套幂等思路(唯一键 + 状态短路)。
     */
    private MallWxRefund createRefund(MallOrder order, BigDecimal amount, Long afterSaleId) {
        MallWxRefund refund = new MallWxRefund();
        refund.setOrderId(order.getId());
        refund.setAfterSaleId(afterSaleId == null ? 0L : afterSaleId);
        refund.setOutRefundNo(numberGenerator.nextRefundNo());
        refund.setRefundAmount(amount);
        refund.setRefundStatus(MallWxRefund.REFUND_STATUS_APPLYING);
        MallWxRefund saved = refundRepository.save(refund);

        // 用独立的 saved 变量而不是复用 refund:lambda 只能捕获 effectively final 的局部变量,
        // 复用同一个变量会让它在 lambda 里不可见(编译期直接报错)
        wxPayClient.refund(order.getOrderNo(), saved.getOutRefundNo(), amount)
                .ifPresent(refundId -> {
                    saved.setWxRefundId(refundId);
                    saved.setRefundStatus(MallWxRefund.REFUND_STATUS_SUCCESS);
                    saved.setCallbackTime(LocalDateTime.now());
                });
        log.info("已发起退款 orderNo={} outRefundNo={} amount={}",
                order.getOrderNo(), saved.getOutRefundNo(), amount);
        return saved;
    }

    private void writeStatusLog(Long orderId, Integer from, int to, int operatorType, Long operatorId,
                                String remark) {
        MallOrderStatusLog statusLog = new MallOrderStatusLog();
        statusLog.setOrderId(orderId);
        statusLog.setFromStatus(from);
        statusLog.setToStatus(to);
        statusLog.setOperatorType(operatorType);
        statusLog.setOperatorId(operatorId);
        statusLog.setRemark(remark);
        statusLogRepository.save(statusLog);
    }

    /** 从审计快照取当前后台用户(管理端操作人)。 */
    private Long currentUserId() {
        var audit = com.minimall.infra.audit.AuditContext.current();
        return audit == null ? null : audit.userId();
    }

    private MallOrder load(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在"));
    }

    private ClientOrderView.Item toItemView(MallOrderItem item) {
        return new ClientOrderView.Item(item.getId(), item.getSkuId(), item.getGoodsId(), item.getGoodsName(),
                item.getSkuName(), item.getGoodsImage(), item.getPrice(), item.getQuantity(),
                item.getTotalAmount(), item.getAfterSaleStatus());
    }

    private AdminOrderView toView(MallOrder order, List<ClientOrderView.Item> items) {
        return new AdminOrderView(order.getId(), order.getOrderNo(), order.getCustomerId(), order.getStatus(),
                order.getGoodsAmount(), order.getFreightAmount(), order.getPromotionDiscountAmount(),
                order.getCouponDiscountAmount(), order.getPayAmount(), order.getReceiverName(),
                order.getReceiverPhone(), order.getReceiverAddress(), order.getRemark(),
                order.getLogisticsCompany(), order.getLogisticsNo(), order.getCloseReason(),
                order.getCreateTime(), order.getPayTime(), order.getShipTime(), items);
    }
}
