package com.minimall.mall.service.impl;

import com.minimall.mall.api.dto.AfterSaleApplyRequest;
import com.minimall.mall.api.dto.AfterSaleView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallAfterSale;
import com.minimall.mall.domain.MallAfterSaleImage;
import com.minimall.mall.domain.MallAfterSaleLog;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderItem;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.MallStockLog;
import com.minimall.mall.domain.MallWxRefund;
import com.minimall.mall.domain.QMallAfterSale;
import com.minimall.mall.domain.repository.MallAfterSaleImageRepository;
import com.minimall.mall.domain.repository.MallAfterSaleLogRepository;
import com.minimall.mall.domain.repository.MallAfterSaleRepository;
import com.minimall.mall.domain.repository.MallOrderItemRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallOrderStatusLogRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.domain.repository.MallStockLogRepository;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
import com.minimall.mall.infra.OrderNumberGenerator;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.infra.pay.WxPayClient;
import com.minimall.mall.service.AfterSaleService;
import com.querydsl.core.BooleanBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 售后实现(商城设计文档 3.9)。
 *
 * <p>四个终态(4/8/9/10)决定了下游动作,这里统一收敛在 {@link #finish} 与
 * {@link #restoreOrderStatus} 两个方法里 —— 状态机有 10 个态,如果每条流转各自写"退款 + 回补库存 +
 * 改订单状态",一定会漏掉某一条(最常见的是漏了回补库存)。
 */
@Service
@Transactional
public class AfterSaleServiceImpl implements AfterSaleService {

    private static final Logger log = LoggerFactory.getLogger(AfterSaleServiceImpl.class);

    /** 未到终态的状态集合:用于"是否还有进行中的售后"。 */
    private static final List<Integer> ACTIVE_STATUSES = List.of(
            MallAfterSale.STATUS_PENDING, MallAfterSale.STATUS_WAIT_RETURN, MallAfterSale.STATUS_WAIT_RECEIVE,
            MallAfterSale.STATUS_REJECTED, MallAfterSale.STATUS_REJECT_RECEIVE, MallAfterSale.STATUS_ARBITRATING);

    private static final int STOCK_RESTORE = 4;
    private static final int BATCH_SIZE = 200;

    private final MallAfterSaleRepository afterSaleRepository;
    private final MallAfterSaleLogRepository logRepository;
    private final MallAfterSaleImageRepository imageRepository;
    private final MallOrderRepository orderRepository;
    private final MallOrderItemRepository orderItemRepository;
    private final MallOrderStatusLogRepository orderStatusLogRepository;
    private final MallSkuRepository skuRepository;
    private final MallStockLogRepository stockLogRepository;
    private final MallWxRefundRepository refundRepository;
    private final OrderNumberGenerator numberGenerator;
    private final WxPayClient wxPayClient;

    public AfterSaleServiceImpl(MallAfterSaleRepository afterSaleRepository,
                               MallAfterSaleLogRepository logRepository,
                               MallAfterSaleImageRepository imageRepository,
                               MallOrderRepository orderRepository,
                               MallOrderItemRepository orderItemRepository,
                               MallOrderStatusLogRepository orderStatusLogRepository,
                               MallSkuRepository skuRepository,
                               MallStockLogRepository stockLogRepository,
                               MallWxRefundRepository refundRepository,
                               OrderNumberGenerator numberGenerator,
                               WxPayClient wxPayClient) {
        this.afterSaleRepository = afterSaleRepository;
        this.logRepository = logRepository;
        this.imageRepository = imageRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderStatusLogRepository = orderStatusLogRepository;
        this.skuRepository = skuRepository;
        this.stockLogRepository = stockLogRepository;
        this.refundRepository = refundRepository;
        this.numberGenerator = numberGenerator;
        this.wxPayClient = wxPayClient;
    }

    // ---------------------------------------------------------------- 小程序端

    @Override
    public Long apply(AfterSaleApplyRequest request) {
        Long customerId = ClientContext.requireCustomerId();
        MallOrderItem item = orderItemRepository.findById(request.orderItemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单明细不存在"));
        MallOrder order = orderRepository.findById(item.getOrderId())
                .filter(value -> Objects.equals(value.getCustomerId(), customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单明细不存在"));

        int type = request.afterSaleType();
        if (type == MallAfterSale.TYPE_REFUND_ONLY) {
            // 3.9:仅退款只允许在"待发货"时申请 —— 已发货还点仅退款,货和钱都在买家那边
            if (order.getStatus() != MallOrder.STATUS_PENDING_SHIP) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "已发货的订单请选择退货退款");
            }
        } else if (order.getStatus() != MallOrder.STATUS_PENDING_SHIP
                && order.getStatus() != MallOrder.STATUS_PENDING_RECEIVE
                && order.getStatus() != MallOrder.STATUS_FINISHED) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "当前订单状态不支持申请售后");
        }
        if (afterSaleRepository.countActiveByOrderItemId(item.getId(), ACTIVE_STATUSES) > 0) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "该商品已有进行中的售后申请");
        }
        if (request.refundAmount().compareTo(item.getTotalAmount()) > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "退款金额不能超过该商品的实付金额");
        }

        MallAfterSale afterSale = new MallAfterSale();
        afterSale.setAfterSaleNo(numberGenerator.nextAfterSaleNo());
        afterSale.setOrderId(order.getId());
        afterSale.setOrderItemId(item.getId());
        afterSale.setCustomerId(customerId);
        afterSale.setAfterSaleType(type);
        afterSale.setStatus(MallAfterSale.STATUS_PENDING);
        afterSale.setApplyReason(request.applyReason());
        afterSale.setApplyDesc(request.applyDesc());
        afterSale.setRefundAmount(request.refundAmount());
        afterSale = afterSaleRepository.save(afterSale);

        saveImages(afterSale.getId(), request.images(), 1, MallAfterSaleImage.STAGE_APPLY);
        writeLog(afterSale.getId(), null, MallAfterSale.STATUS_PENDING,
                MallAfterSaleLog.OPERATOR_BUYER, customerId, "买家申请售后");

        item.setAfterSaleStatus(MallOrderItem.AFTER_SALE_PROCESSING);
        markOrderAfterSale(order);
        return afterSale.getId();
    }

    @Override
    public void cancelByBuyer(Long afterSaleId) {
        Long customerId = ClientContext.requireCustomerId();
        MallAfterSale afterSale = loadOwned(afterSaleId, customerId);
        int status = afterSale.getStatus();
        if (status != MallAfterSale.STATUS_REJECTED && status != MallAfterSale.STATUS_REJECT_RECEIVE) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "当前状态无法撤销");
        }
        finish(afterSale, MallAfterSale.STATUS_CLOSED, MallAfterSaleLog.OPERATOR_BUYER, customerId, "买家撤销申请");
    }

    @Override
    public void submitReturnLogistics(Long afterSaleId, String company, String no) {
        Long customerId = ClientContext.requireCustomerId();
        MallAfterSale afterSale = loadOwned(afterSaleId, customerId);
        if (afterSale.getStatus() != MallAfterSale.STATUS_WAIT_RETURN) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "当前状态不需要提交退货物流");
        }
        if (company == null || company.isBlank() || no == null || no.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请填写退货物流公司与单号");
        }
        int from = afterSale.getStatus();
        afterSale.setReturnLogisticsCompany(company);
        afterSale.setReturnLogisticsNo(no);
        afterSale.setReturnTime(LocalDateTime.now());
        afterSale.setStatus(MallAfterSale.STATUS_WAIT_RECEIVE);
        writeLog(afterSaleId, from, MallAfterSale.STATUS_WAIT_RECEIVE, MallAfterSaleLog.OPERATOR_BUYER,
                customerId, "买家退回商品:" + company + " " + no);
    }

    @Override
    public List<AfterSaleView> mine(Integer status) {
        Long customerId = ClientContext.requireCustomerId();
        QMallAfterSale qAfterSale = QMallAfterSale.mallAfterSale;
        BooleanBuilder where = new BooleanBuilder();
        where.and(qAfterSale.customerId.eq(customerId));
        if (status != null) {
            where.and(qAfterSale.status.eq(status));
        }
        List<MallAfterSale> rows = new ArrayList<>();
        afterSaleRepository.findAll(where, PageRequest.of(0, BATCH_SIZE).withSort(Sort.by("id").descending()))
                .forEach(rows::add);
        return rows.stream().map(afterSale -> toView(afterSale, false)).toList();
    }

    @Override
    public AfterSaleView detailForBuyer(Long afterSaleId) {
        MallAfterSale afterSale = loadOwned(afterSaleId, ClientContext.requireCustomerId());
        return toView(afterSale, true);
    }

    // ---------------------------------------------------------------- 管理端

    @Override
    public PageResult<AfterSaleView> page(Integer status, String afterSaleNo, int pageNo, int pageSize) {
        QMallAfterSale qAfterSale = QMallAfterSale.mallAfterSale;
        BooleanBuilder where = new BooleanBuilder();
        if (status != null) {
            where.and(qAfterSale.status.eq(status));
        }
        if (afterSaleNo != null && !afterSaleNo.isBlank()) {
            where.and(qAfterSale.afterSaleNo.contains(afterSaleNo));
        }
        Page<MallAfterSale> page = afterSaleRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1)).withSort(Sort.by("id").descending()));
        return PageResult.of(page.getTotalElements(),
                page.getContent().stream().map(afterSale -> toView(afterSale, false)).toList());
    }

    @Override
    public AfterSaleView detail(Long afterSaleId) {
        return toView(load(afterSaleId), true);
    }

    @Override
    public void approve(Long afterSaleId, BigDecimal refundAmount) {
        MallAfterSale afterSale = load(afterSaleId);
        if (afterSale.getStatus() != MallAfterSale.STATUS_PENDING) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只有待处理的售后单可以同意");
        }
        int from = afterSale.getStatus();
        if (refundAmount != null) {
            // 3.9:只能下调、不能上调;下调必须留痕
            if (refundAmount.signum() <= 0 || refundAmount.compareTo(afterSale.getRefundAmount()) > 0) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "只能下调退款金额,且必须大于 0");
            }
            if (refundAmount.compareTo(afterSale.getRefundAmount()) < 0) {
                writeLog(afterSaleId, from, from, MallAfterSaleLog.OPERATOR_MERCHANT, currentUserId(),
                        "商家下调退款金额:" + afterSale.getRefundAmount() + " → " + refundAmount);
                afterSale.setRefundAmount(refundAmount);
            }
        }
        if (afterSale.getAfterSaleType() == MallAfterSale.TYPE_REFUND_ONLY) {
            // 仅退款:直接退款并终态(不需要买家退货)
            finish(afterSale, MallAfterSale.STATUS_DONE, MallAfterSaleLog.OPERATOR_MERCHANT, currentUserId(),
                    "商家同意仅退款");
            return;
        }
        afterSale.setStatus(MallAfterSale.STATUS_WAIT_RETURN);
        writeLog(afterSaleId, from, MallAfterSale.STATUS_WAIT_RETURN, MallAfterSaleLog.OPERATOR_MERCHANT,
                currentUserId(), "商家同意退货申请");
    }

    @Override
    public void reject(Long afterSaleId, String reason) {
        MallAfterSale afterSale = load(afterSaleId);
        if (afterSale.getStatus() != MallAfterSale.STATUS_PENDING) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只有待处理的售后单可以拒绝");
        }
        afterSale.setRejectReason(reason);
        int from = afterSale.getStatus();
        afterSale.setStatus(MallAfterSale.STATUS_REJECTED);
        writeLog(afterSaleId, from, MallAfterSale.STATUS_REJECTED, MallAfterSaleLog.OPERATOR_MERCHANT,
                currentUserId(), "商家拒绝申请:" + (reason == null ? "未填写原因" : reason));
        // 回到"未在售后中",买家可以重新申请
        resetItemAfterSaleStatus(afterSale.getOrderItemId());
        restoreOrderStatus(afterSale.getOrderId());
    }

    @Override
    public void confirmReturnReceived(Long afterSaleId, Long newSkuId, String logisticsCompany, String logisticsNo) {
        MallAfterSale afterSale = load(afterSaleId);
        if (afterSale.getStatus() != MallAfterSale.STATUS_WAIT_RECEIVE) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只有待确认收货的售后单可以确认");
        }
        if (afterSale.getAfterSaleType() == MallAfterSale.TYPE_EXCHANGE) {
            // 换货:回补原 SKU 库存 + 扣减新 SKU 库存 + 记录重新发货的物流(3.9)
            if (newSkuId == null) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "换货请选择要更换的规格");
            }
            exchange(afterSale, newSkuId, logisticsCompany, logisticsNo);
            finish(afterSale, MallAfterSale.STATUS_DONE, MallAfterSaleLog.OPERATOR_MERCHANT, currentUserId(),
                    "商家确认收到退货并重新发货");
            return;
        }
        afterSale.setReceiveConfirmTime(LocalDateTime.now());
        finish(afterSale, MallAfterSale.STATUS_DONE, MallAfterSaleLog.OPERATOR_MERCHANT, currentUserId(),
                "商家确认收到退货,执行退款");
    }

    @Override
    public void rejectReturn(Long afterSaleId, String reason) {
        MallAfterSale afterSale = load(afterSaleId);
        if (afterSale.getStatus() != MallAfterSale.STATUS_WAIT_RECEIVE) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只有待确认收货的售后单可以拒绝收货");
        }
        afterSale.setRejectReason(reason);
        int from = afterSale.getStatus();
        afterSale.setStatus(MallAfterSale.STATUS_REJECT_RECEIVE);
        writeLog(afterSaleId, from, MallAfterSale.STATUS_REJECT_RECEIVE, MallAfterSaleLog.OPERATOR_MERCHANT,
                currentUserId(), "商家拒绝收货:" + (reason == null ? "未填写原因" : reason));
    }

    @Override
    public void requestArbitration(Long afterSaleId) {
        MallAfterSale afterSale = load(afterSaleId);
        int status = afterSale.getStatus();
        if (status != MallAfterSale.STATUS_REJECTED && status != MallAfterSale.STATUS_REJECT_RECEIVE) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只有被拒绝的售后单可以申请客服介入");
        }
        afterSale.setStatus(MallAfterSale.STATUS_ARBITRATING);
        afterSale.setArbitrationTime(LocalDateTime.now());
        writeLog(afterSaleId, status, MallAfterSale.STATUS_ARBITRATING, MallAfterSaleLog.OPERATOR_BUYER,
                afterSale.getCustomerId(), "买家申请客服介入");
    }

    @Override
    public void arbitrate(Long afterSaleId, boolean pass, String remark) {
        MallAfterSale afterSale = load(afterSaleId);
        if (afterSale.getStatus() != MallAfterSale.STATUS_ARBITRATING) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只有客服介入中的售后单可以仲裁");
        }
        afterSale.setArbitrationRemark(remark);
        afterSale.setArbitrationTime(LocalDateTime.now());
        if (pass) {
            // 仲裁通过:与商家同意走同一套终态动作(退款/换货),避免两条路径产生不同结果
            finish(afterSale, MallAfterSale.STATUS_ARBITRATION_PASS, MallAfterSaleLog.OPERATOR_PLATFORM,
                    currentUserId(), "客服仲裁通过:" + (remark == null ? "" : remark));
            return;
        }
        int from = afterSale.getStatus();
        afterSale.setStatus(MallAfterSale.STATUS_ARBITRATION_REJECT);
        afterSale.setFinishTime(LocalDateTime.now());
        writeLog(afterSaleId, from, MallAfterSale.STATUS_ARBITRATION_REJECT, MallAfterSaleLog.OPERATOR_PLATFORM,
                currentUserId(), "客服仲裁驳回:" + (remark == null ? "" : remark));
        // 仲裁驳回:不产生库存/资金变动,但要恢复订单状态与明细标记(3.9)
        resetItemAfterSaleStatus(afterSale.getOrderItemId());
        restoreOrderStatus(afterSale.getOrderId());
    }

    // ---------------------------------------------------------------- 定时任务

    @Override
    public int autoApproveTimeout(LocalDateTime updatedBefore) {
        List<MallAfterSale> rows = afterSaleRepository.findTimeoutByStatus(
                MallAfterSale.STATUS_PENDING, updatedBefore, PageRequest.of(0, BATCH_SIZE));
        for (MallAfterSale afterSale : rows) {
            // 每轮都重新取托管实例:上面任何一次 finish 里的库存回补都会清空持久化上下文,
            // 上一轮加载出来的实体到这一轮已经是游离态 —— 直接改它会写不进去,
            // 而日志照样会写"已自动同意",于是出现"日志说同意了、状态却没变"的静默错单
            MallAfterSale target = afterSaleRepository.findById(afterSale.getId()).orElse(null);
            if (target == null) {
                continue;
            }
            int from = target.getStatus();
            if (target.getAfterSaleType() == MallAfterSale.TYPE_REFUND_ONLY) {
                finish(target, MallAfterSale.STATUS_DONE, MallAfterSaleLog.OPERATOR_SYSTEM, null,
                        "商家超时未处理,系统自动同意退款");
            } else {
                target.setStatus(MallAfterSale.STATUS_WAIT_RETURN);
                writeLog(target.getId(), from, MallAfterSale.STATUS_WAIT_RETURN,
                        MallAfterSaleLog.OPERATOR_SYSTEM, null, "商家超时未处理,系统自动同意退货");
            }
        }
        return rows.size();
    }

    @Override
    public int autoCloseTimeout(LocalDateTime updatedBefore) {
        List<MallAfterSale> rows = afterSaleRepository.findTimeoutByStatus(
                MallAfterSale.STATUS_WAIT_RETURN, updatedBefore, PageRequest.of(0, BATCH_SIZE));
        for (MallAfterSale afterSale : rows) {
            finish(afterSale, MallAfterSale.STATUS_CLOSED, MallAfterSaleLog.OPERATOR_SYSTEM, null,
                    "买家超时未退货,系统自动关闭");
        }
        return rows.size();
    }

    @Override
    public int autoReceiveTimeout(LocalDateTime updatedBefore) {
        List<MallAfterSale> rows = afterSaleRepository.findTimeoutByStatus(
                MallAfterSale.STATUS_WAIT_RECEIVE, updatedBefore, PageRequest.of(0, BATCH_SIZE));
        for (MallAfterSale afterSale : rows) {
            afterSale.setReceiveConfirmTime(LocalDateTime.now());
            finish(afterSale, MallAfterSale.STATUS_DONE, MallAfterSaleLog.OPERATOR_SYSTEM, null,
                    "商家超时未确认收货,系统自动确认并执行退款");
        }
        return rows.size();
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 售后终态的统一出口:执行下游动作 → 写日志 → 结算订单与明细状态。
     *
     * @param toStatus 终态(4/8/9/10)
     */
    private void finish(MallAfterSale afterSale, int toStatus, int operatorType, Long operatorId, String remark) {
        // 入口先取一次托管实例:调用方手里的 afterSale 可能已经是游离态 ——
        // 回补库存走的是 @Modifying(clearAutomatically = true),它会清空持久化上下文。
        // 最典型的是换货:exchange 里先回补原规格库存,再回到这里改状态;
        // 对游离态实体 setStatus 不会被持久化,表现是"库存搬了、退款也建了,单据状态却停在待商家收货"
        // (批量超时任务里同理:上一轮清空上下文后,下一轮的实体也失效了)。
        MallAfterSale target = afterSaleRepository.findById(afterSale.getId()).orElse(afterSale);
        int from = target.getStatus();
        boolean refundable = toStatus == MallAfterSale.STATUS_DONE || toStatus == MallAfterSale.STATUS_ARBITRATION_PASS;

        // **先把售后单自身的改动写完**,再做下面的库存/退款动作。
        // 原因与订单取消那里相同:下面的库存回补会 flush 并清空持久化上下文。
        target.setStatus(toStatus);
        target.setFinishTime(LocalDateTime.now());

        if (refundable) {
            // 退货退款/仅退款:创建退款记录并回补库存;换货场景的库存已在 exchange 里处理,这里只退款
            if (target.getAfterSaleType() != MallAfterSale.TYPE_EXCHANGE) {
                createRefund(target);
            }
            if (target.getAfterSaleType() != MallAfterSale.TYPE_EXCHANGE
                    || toStatus == MallAfterSale.STATUS_ARBITRATION_PASS) {
                restoreStock(target);
            }
            markItemAfterSaleDone(target.getOrderItemId());
        } else {
            // 关闭/仲裁驳回:不动资金与库存,但明细要回到"无售后"以便重新申请
            resetItemAfterSaleStatus(target.getOrderItemId());
        }
        writeLog(target.getId(), from, toStatus, operatorType, operatorId, remark);
        // 注意:下面的方法内部都会重新 findById 拿托管实例,所以持久化上下文被清空也不受影响
        restoreOrderStatus(target.getOrderId());
        log.info("售后单结案 afterSaleNo={} from={} to={}", target.getAfterSaleNo(), from, toStatus);
    }

    /** 换货:回补原 SKU、扣减新 SKU,并记录重新发货的物流。 */
    private void exchange(MallAfterSale afterSale, Long newSkuId, String company, String no) {
        MallOrderItem item = orderItemRepository.findById(afterSale.getOrderItemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单明细不存在"));
        Long tenantId = TenantContext.getTenantId();
        // 与 finish 同理:先写售后单的字段,后面的库存条件更新会清空持久化上下文
        afterSale.setReshipLogisticsCompany(company);
        afterSale.setReshipLogisticsNo(no);
        if (Objects.equals(item.getSkuId(), newSkuId)) {
            // 换同一规格没有意义,但也不该报错(可能只是想换一件好的)—— 只记物流
            log.info("换货选择了相同 SKU,仅记录物流 afterSaleNo={}", afterSale.getAfterSaleNo());
        } else {
            MallSku newSku = skuRepository.findById(newSkuId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "更换的规格不存在"));
            if (newSku.availableStock() < item.getQuantity()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "更换的规格库存不足,仅剩 " + newSku.availableStock() + " 件");
            }
            restoreStock(afterSale);
            // 换货的新 SKU 从库存直接扣减(不经过下单流程,所以直接扣 stock)
            skuRepository.restoreStock(newSkuId, tenantId, -item.getQuantity());
            MallStockLog stockLog = new MallStockLog();
            stockLog.setSkuId(newSkuId);
            stockLog.setChangeType(5);
            stockLog.setChangeStock(-item.getQuantity());
            stockLog.setChangeLocked(0);
            stockLog.setBizId(afterSale.getOrderId());
            stockLog.setRemark("换货发出新规格");
            stockLogRepository.save(stockLog);
        }
    }

    /** 退款记录创建(与商家取消订单的退款同一套规则:唯一键 + 状态短路保证幂等)。 */
    private void createRefund(MallAfterSale afterSale) {
        MallWxRefund refund = new MallWxRefund();
        refund.setOrderId(afterSale.getOrderId());
        refund.setAfterSaleId(afterSale.getId());
        refund.setOutRefundNo(numberGenerator.nextRefundNo());
        refund.setRefundAmount(afterSale.getRefundAmount());
        refund.setRefundStatus(MallWxRefund.REFUND_STATUS_APPLYING);
        MallWxRefund saved = refundRepository.save(refund);
        wxPayClient.refund(null, saved.getOutRefundNo(), saved.getRefundAmount())
                .ifPresent(refundId -> {
                    saved.setWxRefundId(refundId);
                    saved.setRefundStatus(MallWxRefund.REFUND_STATUS_SUCCESS);
                    saved.setCallbackTime(LocalDateTime.now());
                });
    }

    /** 退货回补库存 + 写流水。 */
    private void restoreStock(MallAfterSale afterSale) {
        MallOrderItem item = orderItemRepository.findById(afterSale.getOrderItemId()).orElse(null);
        if (item == null) {
            return;
        }
        Long tenantId = TenantContext.getTenantId();
        skuRepository.restoreStock(item.getSkuId(), tenantId, item.getQuantity());
        MallStockLog stockLog = new MallStockLog();
        stockLog.setSkuId(item.getSkuId());
        stockLog.setChangeType(STOCK_RESTORE);
        stockLog.setChangeStock(item.getQuantity());
        stockLog.setChangeLocked(0);
        stockLog.setBizId(afterSale.getOrderId());
        stockLog.setRemark("售后退货回库");
        stockLogRepository.save(stockLog);
    }

    /** 订单进入"售后中"(3.9)。 */
    private void markOrderAfterSale(MallOrder order) {
        if (order.getStatus() != null && order.getStatus() == MallOrder.STATUS_AFTER_SALE) {
            return;
        }
        int from = order.getStatus();
        order.setStatus(MallOrder.STATUS_AFTER_SALE);
        writeOrderStatusLog(order.getId(), from, MallOrder.STATUS_AFTER_SALE, "进入售后中");
    }

    /**
     * 售后终态后回退订单状态(3.9)。
     *
     * <p>规则:该订单仍有进行中的售后 → 保持"售后中";全部明细都已售后完成 → 已取消(整单退掉);
     * 否则回到"支付前/发货后的基准状态"(待发货或已完成)。
     */
    private void restoreOrderStatus(Long orderId) {
        MallOrder order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return;
        }
        if (afterSaleRepository.countActiveByOrderId(orderId, ACTIVE_STATUSES) > 0) {
            return;
        }
        List<MallOrderItem> items = orderItemRepository.findByOrderIdOrderByIdAsc(orderId);
        boolean allDone = !items.isEmpty() && items.stream()
                .allMatch(item -> Objects.equals(item.getAfterSaleStatus(), MallOrderItem.AFTER_SALE_DONE));
        int target;
        if (allDone) {
            // 整单都退完了 —— 订单终态为已取消(3.9)
            target = MallOrder.STATUS_CANCELLED;
        } else if (order.getReceiveTime() != null) {
            target = MallOrder.STATUS_FINISHED;
        } else if (order.getShipTime() != null) {
            target = MallOrder.STATUS_PENDING_RECEIVE;
        } else {
            target = MallOrder.STATUS_PENDING_SHIP;
        }
        int from = order.getStatus();
        if (from == target) {
            return;
        }
        order.setStatus(target);
        if (target == MallOrder.STATUS_CANCELLED) {
            order.setCancelTime(LocalDateTime.now());
            order.setCloseReason(3);
        }
        writeOrderStatusLog(orderId, from, target, "售后结束,订单状态回退");
    }

    private void markItemAfterSaleDone(Long orderItemId) {
        orderItemRepository.findById(orderItemId)
                .ifPresent(item -> item.setAfterSaleStatus(MallOrderItem.AFTER_SALE_DONE));
    }

    private void resetItemAfterSaleStatus(Long orderItemId) {
        orderItemRepository.findById(orderItemId)
                .ifPresent(item -> item.setAfterSaleStatus(MallOrderItem.AFTER_SALE_NONE));
    }

    private void saveImages(Long afterSaleId, List<String> images, int uploaderType, int stage) {
        if (images == null || images.isEmpty()) {
            return;
        }
        for (String url : images) {
            if (url == null || url.isBlank()) {
                continue;
            }
            MallAfterSaleImage image = new MallAfterSaleImage();
            image.setAfterSaleId(afterSaleId);
            image.setImageUrl(url.trim());
            image.setUploaderType(uploaderType);
            image.setStage(stage);
            imageRepository.save(image);
        }
    }

    private void writeLog(Long afterSaleId, Integer fromStatus, int toStatus, int operatorType,
                          Long operatorId, String remark) {
        MallAfterSaleLog logRow = new MallAfterSaleLog();
        logRow.setAfterSaleId(afterSaleId);
        logRow.setFromStatus(fromStatus);
        logRow.setToStatus(toStatus);
        logRow.setOperatorType(operatorType);
        logRow.setOperatorId(operatorId);
        logRow.setRemark(remark);
        logRepository.save(logRow);
    }

    private void writeOrderStatusLog(Long orderId, Integer fromStatus, int toStatus, String remark) {
        var row = new com.minimall.mall.domain.MallOrderStatusLog();
        row.setOrderId(orderId);
        row.setFromStatus(fromStatus);
        row.setToStatus(toStatus);
        row.setOperatorType(com.minimall.mall.domain.MallOrderStatusLog.OPERATOR_SYSTEM);
        row.setRemark(remark);
        orderStatusLogRepository.save(row);
    }

    private Long currentUserId() {
        var audit = com.minimall.infra.audit.AuditContext.current();
        return audit == null ? null : audit.userId();
    }

    private MallAfterSale load(Long afterSaleId) {
        return afterSaleRepository.findById(afterSaleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "售后单不存在"));
    }

    private MallAfterSale loadOwned(Long afterSaleId, Long customerId) {
        MallAfterSale afterSale = load(afterSaleId);
        if (!Objects.equals(afterSale.getCustomerId(), customerId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "售后单不存在");
        }
        return afterSale;
    }

    private AfterSaleView toView(MallAfterSale afterSale, boolean withDetail) {
        AfterSaleView.Item item = null;
        List<AfterSaleView.LogItem> logs = List.of();
        List<String> images = List.of();
        if (withDetail) {
            item = orderItemRepository.findById(afterSale.getOrderItemId())
                    .map(value -> new AfterSaleView.Item(value.getGoodsName(), value.getSkuName(),
                            value.getGoodsImage(), value.getPrice(), value.getQuantity()))
                    .orElse(null);
            logs = logRepository.findByAfterSaleIdOrderByIdAsc(afterSale.getId()).stream()
                    .map(row -> new AfterSaleView.LogItem(row.getFromStatus(), row.getToStatus(),
                            row.getOperatorType(), row.getRemark(), row.getCreateTime()))
                    .toList();
            images = imageRepository.findByAfterSaleIdOrderByIdAsc(afterSale.getId()).stream()
                    .map(MallAfterSaleImage::getImageUrl).toList();
        }
        MallOrder order = orderRepository.findById(afterSale.getOrderId()).orElse(null);
        return new AfterSaleView(afterSale.getId(), afterSale.getAfterSaleNo(), afterSale.getOrderId(),
                order == null ? null : order.getOrderNo(), afterSale.getOrderItemId(), afterSale.getCustomerId(),
                afterSale.getAfterSaleType(), afterSale.getStatus(), statusText(afterSale.getStatus()),
                afterSale.getApplyReason(), afterSale.getApplyDesc(), afterSale.getRefundAmount(),
                afterSale.getRejectReason(), afterSale.getReturnLogisticsCompany(),
                afterSale.getReturnLogisticsNo(), afterSale.getReshipLogisticsCompany(),
                afterSale.getReshipLogisticsNo(), afterSale.getArbitrationRemark(),
                afterSale.getCreateTime(), afterSale.getFinishTime(), images, item, logs);
    }

    private String statusText(int status) {
        return switch (status) {
            case MallAfterSale.STATUS_PENDING -> "待商家处理";
            case MallAfterSale.STATUS_WAIT_RETURN -> "待买家退货";
            case MallAfterSale.STATUS_WAIT_RECEIVE -> "待商家收货";
            case MallAfterSale.STATUS_DONE -> "售后完成";
            case MallAfterSale.STATUS_REJECTED -> "商家已拒绝";
            case MallAfterSale.STATUS_REJECT_RECEIVE -> "商家拒绝收货";
            case MallAfterSale.STATUS_ARBITRATING -> "客服介入中";
            case MallAfterSale.STATUS_ARBITRATION_PASS -> "仲裁通过";
            case MallAfterSale.STATUS_ARBITRATION_REJECT -> "仲裁驳回";
            case MallAfterSale.STATUS_CLOSED -> "已关闭";
            default -> "未知";
        };
    }
}
