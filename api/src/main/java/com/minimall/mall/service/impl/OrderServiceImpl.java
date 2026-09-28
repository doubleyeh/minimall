package com.minimall.mall.service.impl;

import com.minimall.mall.api.dto.ClientOrderView;
import com.minimall.mall.api.dto.CreateOrderRequest;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.api.dto.OrderPreviewRequest;
import com.minimall.mall.api.dto.OrderPreviewView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallCart;
import com.minimall.mall.domain.MallCoupon;
import com.minimall.mall.domain.MallCouponRecord;
import com.minimall.mall.domain.MallCustomerAddress;
import com.minimall.mall.domain.MallFreightTemplate;
import com.minimall.mall.domain.MallFreightTemplateRule;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderItem;
import com.minimall.mall.domain.MallOrderStatusLog;
import com.minimall.mall.domain.MallPromotionFullReduction;
import com.minimall.mall.domain.MallPromotionFullReductionScope;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.MallStockLog;
import com.minimall.mall.domain.MallWxPayment;
import com.minimall.mall.domain.QMallOrder;
import com.minimall.mall.domain.repository.MallCartRepository;
import com.minimall.mall.domain.repository.MallCouponRecordRepository;
import com.minimall.mall.domain.repository.MallCouponRepository;
import com.minimall.mall.domain.repository.MallCustomerAddressRepository;
import com.minimall.mall.domain.repository.MallFreightTemplateRepository;
import com.minimall.mall.domain.repository.MallFreightTemplateRuleRepository;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallOrderItemRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallOrderStatusLogRepository;
import com.minimall.mall.domain.repository.MallPromotionFullReductionRepository;
import com.minimall.mall.domain.repository.MallPromotionFullReductionScopeRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.domain.repository.MallStockLogRepository;
import com.minimall.mall.domain.repository.MallWxPaymentRepository;
import com.minimall.mall.infra.OrderNumberGenerator;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.service.MemberPointsService;
import com.minimall.mall.service.OrderService;
import com.minimall.mall.service.PayService;
import com.minimall.mall.service.support.OrderAmountCalculator;
import com.querydsl.core.BooleanBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 订单实现(商城设计文档 3.3、3.4、3.5、3.7)。
 *
 * <p>下单这条路径上有三处"顺序写错就会出事故"的地方,代码里都标了注释:
 * <ol>
 *   <li><b>先落订单再锁库存</b>:库存用条件更新,失败必须让整个事务回滚(不能"记一笔失败日志继续")</li>
 *   <li><b>优惠券核销的受影响行数为 0 时必须抛异常</b>:说明券已被别的订单用掉,
 *       继续下去就是"一张券抵扣两单"</li>
 *   <li><b>金额一律过 {@code money()}</b>:任何直接落库的 BigDecimal 都要规整为两位小数,
 *       否则库里会出现 10.999999999 这种金额,对账时无法解释</li>
 * </ol>
 */
@Service
@Transactional
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private static final int STATUS_ENABLED = 1;
    private static final int SELECTED = 1;
    private static final int COUPON_UNUSED = 1;
    private static final int OPERATOR_BUYER = MallOrderStatusLog.OPERATOR_BUYER;
    private static final int OPERATOR_SYSTEM = MallOrderStatusLog.OPERATOR_SYSTEM;
    private static final int STOCK_LOCK = 1;
    private static final int STOCK_RELEASE = 2;
    /** 单次定时任务处理的最大订单数,避免一次扫太多把数据库拖住;剩余的下个周期继续。 */
    private static final int BATCH_SIZE = 200;

    private final MallOrderRepository orderRepository;
    private final MallOrderItemRepository orderItemRepository;
    private final MallOrderStatusLogRepository statusLogRepository;
    private final MallCartRepository cartRepository;
    private final MallSkuRepository skuRepository;
    private final MallGoodsRepository goodsRepository;
    private final MallCustomerAddressRepository addressRepository;
    private final MallCouponRepository couponRepository;
    private final MallCouponRecordRepository couponRecordRepository;
    private final MallFreightTemplateRepository freightTemplateRepository;
    private final MallFreightTemplateRuleRepository freightRuleRepository;
    private final MallPromotionFullReductionRepository promotionRepository;
    private final MallPromotionFullReductionScopeRepository promotionScopeRepository;
    private final MallStockLogRepository stockLogRepository;
    private final MallWxPaymentRepository paymentRepository;
    private final OrderAmountCalculator calculator;
    private final MemberPointsService memberPointsService;
    private final PayService payService;
    private final OrderNumberGenerator numberGenerator;

    public OrderServiceImpl(MallOrderRepository orderRepository,
                            MallOrderItemRepository orderItemRepository,
                            MallOrderStatusLogRepository statusLogRepository,
                            MallCartRepository cartRepository,
                            MallSkuRepository skuRepository,
                            MallGoodsRepository goodsRepository,
                            MallCustomerAddressRepository addressRepository,
                            MallCouponRepository couponRepository,
                            MallCouponRecordRepository couponRecordRepository,
                            MallFreightTemplateRepository freightTemplateRepository,
                            MallFreightTemplateRuleRepository freightRuleRepository,
                            MallPromotionFullReductionRepository promotionRepository,
                            MallPromotionFullReductionScopeRepository promotionScopeRepository,
                            MallStockLogRepository stockLogRepository,
                            MallWxPaymentRepository paymentRepository,
                            OrderAmountCalculator calculator,
                            MemberPointsService memberPointsService,
                            PayService payService,
                            OrderNumberGenerator numberGenerator) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.statusLogRepository = statusLogRepository;
        this.cartRepository = cartRepository;
        this.skuRepository = skuRepository;
        this.goodsRepository = goodsRepository;
        this.addressRepository = addressRepository;
        this.couponRepository = couponRepository;
        this.couponRecordRepository = couponRecordRepository;
        this.freightTemplateRepository = freightTemplateRepository;
        this.freightRuleRepository = freightRuleRepository;
        this.promotionRepository = promotionRepository;
        this.promotionScopeRepository = promotionScopeRepository;
        this.stockLogRepository = stockLogRepository;
        this.paymentRepository = paymentRepository;
        this.calculator = calculator;
        this.memberPointsService = memberPointsService;
        this.payService = payService;
        this.numberGenerator = numberGenerator;
    }

    @Override
    public OrderCreateResponse create(CreateOrderRequest request) {
        Long customerId = ClientContext.requireCustomerId();
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (request.addressId() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择收货地址");
        }

        // 算价与校验全部走 prepare:与结算试算共用同一段,保证"端上看到的价"与"实付"逐分一致
        PreparedOrder prepared = prepare(request.items(), request.addressId(), request.couponRecordId(),
                request.pointsToUse(), customerId);
        MallCustomerAddress address = prepared.address();

        MallOrder order = new MallOrder();
        order.setOrderNo(numberGenerator.nextOrderNo());
        order.setCustomerId(customerId);
        order.setStatus(MallOrder.STATUS_PENDING_PAY);
        order.setGoodsAmount(prepared.goodsAmount());
        order.setFreightAmount(calculator.money(prepared.freight()));
        order.setPromotionDiscountAmount(prepared.promotionDiscount());
        order.setCouponDiscountAmount(prepared.couponDiscount());
        order.setPointsDiscountAmount(prepared.pointsDiscount());
        order.setPointsUsed(prepared.pointsUsed());
        order.setPayAmount(prepared.payAmount());
        order.setCouponRecordId(prepared.couponUse().recordId());
        order.setReceiverName(address.getReceiverName());
        order.setReceiverPhone(address.getReceiverPhone());
        order.setReceiverAddress(address.getProvince() + address.getCity() + address.getDistrict()
                + address.getDetailAddress());
        order.setRemark(request.remark());
        order = orderRepository.save(order);

        for (Line line : prepared.lines()) {
            MallOrderItem item = new MallOrderItem();
            item.setOrderId(order.getId());
            item.setSkuId(line.sku().getId());
            item.setGoodsId(line.goods().getId());
            item.setGoodsName(line.goods().getGoodsName());
            item.setSkuName(line.sku().getSkuName());
            item.setGoodsImage(line.sku().getSkuImage() != null
                    ? line.sku().getSkuImage() : line.goods().getMainImage());
            item.setPrice(line.sku().getPrice());
            item.setQuantity(line.quantity());
            item.setTotalAmount(calculator.money(line.totalAmount()));
            item.setAfterSaleStatus(MallOrderItem.AFTER_SALE_NONE);
            orderItemRepository.save(item);

            // 锁库存:条件更新受影响行数为 0 表示可售不足(并发下单抢最后一件),
            // 此时必须抛异常让整个事务回滚 —— 已经落库的订单与明细会一起撤销
            int affected = skuRepository.lockStock(line.sku().getId(), tenantId, line.quantity());
            if (affected == 0) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "「" + line.sku().getSkuName() + "」库存不足,请调整数量后重试");
            }
            writeStockLog(line.sku().getId(), STOCK_LOCK, 0, line.quantity(), order.getId(), "下单锁定");
        }

        if (prepared.couponUse().recordId() != null) {
            int used = couponRecordRepository.useForOrder(prepared.couponUse().recordId(), customerId, tenantId,
                    order.getId(), LocalDateTime.now());
            if (used == 0) {
                // 券在"校验"与"核销"之间被别的订单用掉了(同一张券开了两个结算页)
                throw new BusinessException(ErrorCode.DATA_CONFLICT, "优惠券已被使用,请重新选择");
            }
        }

        // 积分预扣(3.11):与库存/券同一个事务,扣不动就整单回滚 ——
        // 等到支付成功才扣的话,"下单到支付"之间积分会被另一单用掉,那时支付金额已经定了,只能少收钱。
        // 占用明细记在 mall_points_use 里,订单关闭时据此退回原批次
        if (prepared.pointsUsed() > 0) {
            memberPointsService.redeem(customerId, order.getId(), prepared.pointsUsed());
        }

        if (prepared.fromCart()) {
            cartRepository.deleteAll(cartRepository.findByCustomerIdOrderByIdDesc(customerId).stream()
                    .filter(cart -> Objects.equals(cart.getSelected(), SELECTED))
                    .toList());
        }

        writeStatusLog(order.getId(), null, MallOrder.STATUS_PENDING_PAY, OPERATOR_BUYER, customerId, "创建订单");

        // 3.3 第 7 步的"创建支付流水"落在下单事务内(统一下单的**网络调用**才在事务外,见 PayService)。
        // 先有流水很重要:支付回调是按商户订单号定位流水的 —— 没有它,回调会因为"找不到这笔支付"被忽略,
        // 表现为"用户明明付款成功,订单却一直是待支付"。
        MallWxPayment payment = new MallWxPayment();
        payment.setOrderId(order.getId());
        payment.setOutTradeNo(order.getOrderNo());
        payment.setPayAmount(prepared.payAmount());
        payment.setPayStatus(MallWxPayment.PAY_STATUS_PENDING);
        paymentRepository.save(payment);

        // 0 元订单(满减/券把实付打到 0)不留在一个"付不掉"的待支付里:它不走支付渠道,
        // 而 prepay 对它会直接拒绝,用户既付不了也等不到超时关单之外的结果。
        // 放在状态日志之后,让流水顺序是"创建订单(null→待支付)"→"自动支付(待支付→待发货)"
        payService.settleFreeOrder(order.getId());

        log.info("订单创建成功 tenantId={} customerId={} orderNo={} payAmount={} pointsUsed={}",
                tenantId, customerId, order.getOrderNo(), prepared.payAmount(), prepared.pointsUsed());

        return new OrderCreateResponse(order.getId(), order.getOrderNo(),
                order.getGoodsAmount(), order.getFreightAmount(), order.getPromotionDiscountAmount(),
                order.getCouponDiscountAmount(), order.getPointsDiscountAmount(), order.getPayAmount(),
                order.getPointsUsed(), null);
    }

    @Override
    public OrderPreviewView preview(OrderPreviewRequest request) {
        Long customerId = ClientContext.requireCustomerId();
        if (TenantContext.getTenantId() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        PreparedOrder prepared = prepare(request.items(), request.addressId(), request.couponRecordId(),
                request.pointsToUse(), customerId);
        List<OrderPreviewView.Item> items = prepared.lines().stream()
                .map(line -> new OrderPreviewView.Item(line.sku().getId(), line.goods().getId(),
                        line.goods().getGoodsName(), line.sku().getSkuName(),
                        line.sku().getSkuImage() != null ? line.sku().getSkuImage() : line.goods().getMainImage(),
                        line.sku().getPrice(), line.quantity(), line.totalAmount(),
                        line.sku().availableStock()))
                .toList();
        return new OrderPreviewView(items, prepared.goodsAmount(), prepared.promotionDiscount(),
                prepared.couponDiscount(), prepared.pointsDiscount(), calculator.money(prepared.freight()),
                prepared.payAmount(), prepared.pointsUsed(), prepared.maxRedeemPoints(),
                calculator.pointsToMoney(prepared.maxRedeemPoints()),
                prepared.customerPoints(), prepared.address() == null);
    }

    /**
     * 结算准备:商品行 → 五种金额(3.5 的顺序 + 3.11 的积分抵现)。
     *
     * <p><b>试算与下单共用这一段是有意的</b>:端上自己算一遍的话,运费(模板/区域/包邮)、满减(活动+范围+阶梯)、
     * 券门槛、积分上限任何一处漂移,方向都是**少收钱**。这里只读不写,除了积分可用量会顺带做一次懒过期。
     *
     * @param addressId 可空:试算时允许还没选地址(运费按 0 计并置 needAddress),下单由调用方保证非空
     */
    private PreparedOrder prepare(List<CreateOrderRequest.Item> items, Long addressId, Long couponRecordId,
                                  Integer pointsToUse, Long customerId) {
        boolean fromCart = items == null || items.isEmpty();
        List<Line> lines = fromCart ? linesFromCart(customerId) : linesFromRequest(items, customerId);
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "没有可结算的商品");
        }

        MallCustomerAddress address = null;
        if (addressId != null) {
            address = addressRepository.findById(addressId)
                    .filter(item -> Objects.equals(item.getCustomerId(), customerId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "收货地址不存在"));
        }

        BigDecimal goodsAmount = calculator.money(lines.stream().map(Line::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        // ① 满减(3.5):同一订单只取减免最大的一个活动
        OrderAmountCalculator.PromotionHit promotion = bestPromotion(lines, goodsAmount);
        BigDecimal promotionDiscount = promotion == null ? BigDecimal.ZERO : calculator.money(promotion.discount());
        BigDecimal amountAfterPromotion = goodsAmount.subtract(promotionDiscount);

        // ② 优惠券:门槛按"满减后的商品金额"判断(3.5)
        CouponUse couponUse = resolveCoupon(couponRecordId, customerId, amountAfterPromotion);
        BigDecimal couponDiscount = couponUse.discount();

        // ③ 积分抵现(3.11):上限由服务端重算,端上传来的只是意向 —— 超限直接报错而不是静默夹取,
        //    静默夹取会让"端上预览的价"与"实际实付"对不上
        int customerPoints = memberPointsService.usablePoints(customerId);
        int maxRedeemPoints = calculator.maxRedeemPoints(goodsAmount, promotionDiscount, couponDiscount,
                customerPoints);
        int pointsUsed = pointsToUse == null ? 0 : pointsToUse;
        if (pointsUsed > maxRedeemPoints) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "本单最多可用 " + maxRedeemPoints + " 积分(可用 " + customerPoints + "),请调整后重试");
        }
        BigDecimal pointsDiscount = calculator.pointsToMoney(pointsUsed);

        // ④ 运费(3.7):按商品金额与数量/重量分组计算
        BigDecimal freight = address == null ? BigDecimal.ZERO : freightAmount(lines, address.getProvince());

        BigDecimal payAmount = calculator.payable(goodsAmount, promotionDiscount, couponDiscount,
                pointsDiscount, freight);

        return new PreparedOrder(lines, address, fromCart, goodsAmount, promotionDiscount, couponUse,
                couponDiscount, pointsUsed, pointsDiscount, freight, payAmount, maxRedeemPoints, customerPoints);
    }

    @Override
    public void cancel(Long orderId) {
        Long customerId = ClientContext.requireCustomerId();
        Long tenantId = TenantContext.getTenantId();
        MallOrder order = loadOwned(orderId, customerId);
        if (order.getStatus() != MallOrder.STATUS_PENDING_PAY) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只有待支付的订单可以取消");
        }
        releaseOrderResources(order, tenantId, MallOrder.STATUS_CANCELLED, 2, OPERATOR_BUYER, customerId, "买家取消");
    }

    @Override
    public void confirmReceive(Long orderId) {
        Long customerId = ClientContext.requireCustomerId();
        Long tenantId = TenantContext.getTenantId();
        MallOrder order = loadOwned(orderId, customerId);
        if (order.getStatus() != MallOrder.STATUS_PENDING_RECEIVE) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只有待收货的订单可以确认收货");
        }
        finishAndGrant(order, tenantId, OPERATOR_BUYER, customerId, "确认收货");
    }

    /**
     * 确认收货:改状态 + 发积分与成长值(3.4)。
     *
     * <p><b>买家手动确认与系统自动确认共用这一段</b>。两条路径原本各写一份"改状态 + 两个时间戳 + 写日志",
     * 如果把发积分只加在其中一条上,自动确认那条会静默漏发 —— 而自动确认每小时都在跑,漏了要等用户投诉才发现。
     */
    private void finishAndGrant(MallOrder order, Long tenantId, int operatorType, Long operatorId, String remark) {
        int from = order.getStatus();
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(MallOrder.STATUS_FINISHED);
        order.setReceiveTime(now);
        order.setFinishTime(now);
        // 先显式 UPDATE 落状态:下面的发放会做 clearAutomatically 的余额/批次更新,
        // 批处理里第 2 笔起的订单随即游离,只改实体会丢(与 releaseOrderResources 同一个坑)
        orderRepository.updateStatusOnFinish(order.getId(), tenantId, MallOrder.STATUS_FINISHED, now, now);

        // 发放以订单为幂等键:两条路径重复触发只会发一次
        memberPointsService.grant(order.getCustomerId(), order.getId(), order.getPayAmount());

        // 日志放在发放之后:发放里的批量更新带 flushAutomatically,放在前面有被清掉的风险
        writeStatusLog(order.getId(), from, MallOrder.STATUS_FINISHED, operatorType, operatorId, remark);
    }

    @Override
    public PageResult<ClientOrderView> list(Integer status, int pageNo, int pageSize) {
        Long customerId = ClientContext.requireCustomerId();
        QMallOrder qOrder = QMallOrder.mallOrder;
        BooleanBuilder where = new BooleanBuilder();
        where.and(qOrder.customerId.eq(customerId));
        if (status != null) {
            where.and(qOrder.status.eq(status));
        }
        Page<MallOrder> page = orderRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1)));
        List<ClientOrderView> views = page.getContent().stream()
                .map(order -> toView(order, orderItemRepository.findByOrderIdOrderByIdAsc(order.getId())))
                .toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public ClientOrderView detail(Long orderId) {
        Long customerId = ClientContext.requireCustomerId();
        MallOrder order = loadOwned(orderId, customerId);
        return toView(order, orderItemRepository.findByOrderIdOrderByIdAsc(orderId));
    }

    @Override
    public int closeTimeoutOrders(LocalDateTime createdBefore) {
        Long tenantId = TenantContext.getTenantId();
        List<MallOrder> orders = orderRepository.findTimeoutPendingOrders(MallOrder.STATUS_PENDING_PAY,
                createdBefore, PageRequest.of(0, BATCH_SIZE));
        for (MallOrder order : orders) {
            releaseOrderResources(order, tenantId, MallOrder.STATUS_CANCELLED, 1, OPERATOR_SYSTEM, null, "超时未支付,系统关闭");
        }
        return orders.size();
    }

    @Override
    public int autoReceiveOrders(LocalDateTime shippedBefore) {
        Long tenantId = TenantContext.getTenantId();
        List<MallOrder> orders = orderRepository.findAutoReceiveOrders(MallOrder.STATUS_PENDING_RECEIVE,
                shippedBefore, PageRequest.of(0, BATCH_SIZE));
        for (MallOrder order : orders) {
            // 与买家手动确认走同一段:发积分不能只加在手动那条路径上
            finishAndGrant(order, tenantId, OPERATOR_SYSTEM, null, "发货后超时,系统自动确认收货");
        }
        return orders.size();
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 释放订单占用的资源:锁定库存 + 优惠券 + 状态流转。
     *
     * <p>取消与超时关闭共用这一段,是刻意的:**两条路径必须做完全一样的事**。
     * 各写一份的结果通常是"超时关闭忘了退券",而这个问题要等买家投诉才会被发现。
     */
    private void releaseOrderResources(MallOrder order, Long tenantId, int toStatus, int closeReason,
                                       int operatorType, Long operatorId, String remark) {
        int from = order.getStatus();

        // 状态必须用显式 UPDATE 落库。下面的库存/优惠券更新是 @Modifying(clearAutomatically = true),
        // 会清空持久化上下文:超时关闭是批处理,同批第 2 个及之后的 order 随即成为游离对象,
        // 只改实体的话提交时不会落库 —— 表现为"库存退了、日志写了系统关闭,订单还是待支付"
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(toStatus);
        order.setCloseReason(closeReason);
        order.setCancelTime(now);
        orderRepository.updateStatusOnClose(order.getId(), tenantId, toStatus, closeReason, now);

        for (MallOrderItem item : orderItemRepository.findByOrderIdOrderByIdAsc(order.getId())) {
            int affected = skuRepository.releaseLockedStock(item.getSkuId(), tenantId, item.getQuantity());
            if (affected == 0) {
                // 已经释放过(重复关闭/人工干预):记日志即可,不要让异常把整批任务打断
                log.warn("释放锁定库存时受影响行数为 0(可能已释放) orderId={} skuId={}",
                        order.getId(), item.getSkuId());
            }
            writeStockLog(item.getSkuId(), STOCK_RELEASE, 0, -item.getQuantity(), order.getId(), remark);
        }
        // 未支付关闭要把券还给买家,否则买家白丢一张券(3.4)
        couponRecordRepository.releaseByOrder(order.getId(), tenantId);
        // 预扣的积分退回**原批次**(3.11)。这里面的更新同样带 clearAutomatically,
        // 但上面已用显式 UPDATE 落过状态,所以批处理里第 2 笔起的游离对象不会丢改动
        memberPointsService.returnForOrder(order.getId(), order.getCustomerId());

        writeStatusLog(order.getId(), from, toStatus, operatorType, operatorId, remark);
    }

    /**
     * 满减:先按活动范围筛出"这个活动能覆盖到的商品金额",再交给计算器取减免最大的一档(3.5)。
     */
    private OrderAmountCalculator.PromotionHit bestPromotion(List<Line> lines, BigDecimal goodsAmount) {
        List<MallPromotionFullReduction> activities = promotionRepository.findActive(LocalDateTime.now());
        if (activities.isEmpty()) {
            return null;
        }
        Map<Long, List<MallPromotionFullReductionScope>> scopesByActivity = new HashMap<>();
        promotionScopeRepository.findByActivityIdIn(activities.stream()
                        .map(MallPromotionFullReduction::getId).toList())
                .forEach(scope -> scopesByActivity.computeIfAbsent(scope.getActivityId(), key -> new ArrayList<>())
                        .add(scope));

        List<OrderAmountCalculator.PromotionCandidate> candidates = new ArrayList<>();
        for (MallPromotionFullReduction activity : activities) {
            BigDecimal covered;
            if (activity.getScopeType() != null && activity.getScopeType() == MallPromotionFullReduction.SCOPE_ALL) {
                covered = goodsAmount;
            } else {
                List<Long> scopeIds = scopesByActivity.getOrDefault(activity.getId(), List.of()).stream()
                        .map(MallPromotionFullReductionScope::getScopeId).toList();
                if (scopeIds.isEmpty()) {
                    continue;
                }
                covered = lines.stream()
                        .filter(line -> activity.getScopeType() != null
                                && activity.getScopeType() == MallPromotionFullReduction.SCOPE_GOODS
                                ? scopeIds.contains(line.goods().getId())
                                : scopeIds.contains(line.goods().getCategoryId()))
                        .map(Line::totalAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            if (covered.signum() > 0) {
                candidates.add(new OrderAmountCalculator.PromotionCandidate(activity.getId(),
                        activity.getActivityName(), activity.getReductionRule(), covered));
            }
        }
        return calculator.bestFullReduction(candidates).orElse(null);
    }

    /** 优惠券校验与可抵扣金额;未使用优惠券时 {@code recordId} 为空。 */
    private CouponUse resolveCoupon(Long couponRecordId, Long customerId, BigDecimal amountAfterPromotion) {
        if (couponRecordId == null) {
            return new CouponUse(null, BigDecimal.ZERO);
        }
        MallCouponRecord record = couponRecordRepository.findById(couponRecordId)
                .filter(item -> Objects.equals(item.getCustomerId(), customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "优惠券不存在"));
        if (record.getStatus() == null || record.getStatus() != COUPON_UNUSED) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "优惠券不可用");
        }
        MallCoupon coupon = couponRepository.findById(record.getCouponId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "优惠券不存在"));
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getStatus() == null || coupon.getStatus() != STATUS_ENABLED
                || coupon.getValidStartTime().isAfter(now) || coupon.getValidEndTime().isBefore(now)) {
            // 定时任务还没跑到也不能让过期券生效(3.10 的双保险)
            throw new BusinessException(ErrorCode.PARAM_INVALID, "优惠券已过期或已停用");
        }
        BigDecimal discount = calculator.couponDiscount(coupon, amountAfterPromotion);
        if (discount.signum() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "未达到优惠券使用门槛(满 " + coupon.getMinOrderAmount() + " 可用)");
        }
        return new CouponUse(record.getId(), discount);
    }

    /** 运费(3.7):按运费模板分组计算,无模板的商品视为包邮。 */
    private BigDecimal freightAmount(List<Line> lines, String province) {
        Map<Long, List<Line>> byTemplate = new LinkedHashMap<>();
        for (Line line : lines) {
            Long templateId = line.goods().getFreightTemplateId();
            if (templateId == null) {
                continue;
            }
            byTemplate.computeIfAbsent(templateId, key -> new ArrayList<>()).add(line);
        }
        if (byTemplate.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Map<Long, MallFreightTemplate> templates = new HashMap<>();
        freightTemplateRepository.findAllById(byTemplate.keySet())
                .forEach(template -> templates.put(template.getId(), template));
        Map<Long, List<MallFreightTemplateRule>> rulesByTemplate = new HashMap<>();
        freightRuleRepository.findByTemplateIdInOrderByIdAsc(byTemplate.keySet())
                .forEach(rule -> rulesByTemplate.computeIfAbsent(rule.getTemplateId(), key -> new ArrayList<>())
                        .add(rule));

        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, List<Line>> entry : byTemplate.entrySet()) {
            MallFreightTemplate template = templates.get(entry.getKey());
            if (template == null) {
                continue;
            }
            List<Line> groupLines = entry.getValue();
            BigDecimal groupAmount = groupLines.stream().map(Line::totalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal quantity = BigDecimal.valueOf(groupLines.stream()
                    .mapToInt(Line::quantity).sum());
            BigDecimal weight = groupLines.stream()
                    .map(line -> line.sku().getWeight() == null ? BigDecimal.ZERO
                            : line.sku().getWeight().multiply(BigDecimal.valueOf(line.quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            OrderAmountCalculator.FreightGroup group = new OrderAmountCalculator.FreightGroup(
                    template.getId(), template.getChargeType(), quantity, weight, groupAmount);
            total = total.add(calculator.freight(group,
                    rulesByTemplate.getOrDefault(template.getId(), List.of()), province));
        }
        return total;
    }

    private List<Line> linesFromRequest(List<CreateOrderRequest.Item> items, Long customerId) {
        List<Line> lines = new ArrayList<>();
        for (CreateOrderRequest.Item item : items) {
            lines.add(buildLine(item.skuId(), item.quantity()));
        }
        return lines;
    }

    private List<Line> linesFromCart(Long customerId) {
        List<Line> lines = new ArrayList<>();
        for (MallCart cart : cartRepository.findByCustomerIdOrderByIdDesc(customerId)) {
            if (!Objects.equals(cart.getSelected(), SELECTED)) {
                continue;
            }
            lines.add(buildLine(cart.getSkuId(), cart.getQuantity()));
        }
        return lines;
    }

    private Line buildLine(Long skuId, int quantity) {
        MallSku sku = skuRepository.findById(skuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品规格不存在"));
        MallGoods goods = goodsRepository.findById(sku.getGoodsId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品不存在"));
        if (sku.getStatus() == null || sku.getStatus() != STATUS_ENABLED
                || goods.getStatus() == null || goods.getStatus() != STATUS_ENABLED) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "「" + goods.getGoodsName() + "」已下架或停售,请从购物车移除");
        }
        if (sku.availableStock() < quantity) {
            // 这一步只是"提前给出可读的错误" —— 真正的并发保护在下单时的条件更新(见 lockStock 的注释)
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "「" + sku.getSkuName() + "」库存不足,仅剩 " + sku.availableStock() + " 件");
        }
        BigDecimal total = calculator.money(sku.getPrice().multiply(BigDecimal.valueOf(quantity)));
        return new Line(sku, goods, quantity, total);
    }

    private MallOrder loadOwned(Long orderId, Long customerId) {
        MallOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单不存在"));
        if (!Objects.equals(order.getCustomerId(), customerId)) {
            // 别人的订单一律按"不存在"处理,不泄露它是否存在
            throw new BusinessException(ErrorCode.NOT_FOUND, "订单不存在");
        }
        return order;
    }

    private void writeStockLog(Long skuId, int changeType, int changeStock, int changeLocked,
                               Long bizId, String remark) {
        MallStockLog log = new MallStockLog();
        log.setSkuId(skuId);
        log.setChangeType(changeType);
        log.setChangeStock(changeStock);
        log.setChangeLocked(changeLocked);
        log.setBizId(bizId);
        log.setRemark(remark);
        stockLogRepository.save(log);
    }

    private void writeStatusLog(Long orderId, Integer fromStatus, int toStatus, int operatorType,
                                Long operatorId, String remark) {
        MallOrderStatusLog statusLog = new MallOrderStatusLog();
        statusLog.setOrderId(orderId);
        statusLog.setFromStatus(fromStatus);
        statusLog.setToStatus(toStatus);
        statusLog.setOperatorType(operatorType);
        statusLog.setOperatorId(operatorId);
        statusLog.setRemark(remark);
        statusLogRepository.save(statusLog);
    }

    private ClientOrderView toView(MallOrder order, List<MallOrderItem> items) {
        List<ClientOrderView.Item> viewItems = items.stream()
                .map(item -> new ClientOrderView.Item(item.getId(), item.getSkuId(), item.getGoodsId(),
                        item.getGoodsName(), item.getSkuName(), item.getGoodsImage(), item.getPrice(),
                        item.getQuantity(), item.getTotalAmount(), item.getAfterSaleStatus()))
                .toList();
        return new ClientOrderView(order.getId(), order.getOrderNo(), order.getStatus(),
                order.getGoodsAmount(), order.getFreightAmount(), order.getPromotionDiscountAmount(),
                order.getCouponDiscountAmount(), order.getPointsDiscountAmount(), order.getPayAmount(),
                order.getPointsUsed(), order.getReceiverName(),
                order.getReceiverPhone(), order.getReceiverAddress(), order.getRemark(),
                order.getLogisticsCompany(), order.getLogisticsNo(), order.getCloseReason(),
                order.getCreateTime(), order.getPayTime(), order.getShipTime(), order.getReceiveTime(),
                order.getFinishTime(), viewItems);
    }

    /** 下单行:SKU + 商品 + 数量 + 行金额。 */
    private record Line(MallSku sku, MallGoods goods, int quantity, BigDecimal totalAmount) {
    }

    private record CouponUse(Long recordId, BigDecimal discount) {
    }

    /** 结算准备的结果(3.5 的五种金额 + 3.11 的积分)。试算与下单共用,见 {@link #prepare}。 */
    private record PreparedOrder(List<Line> lines, MallCustomerAddress address, boolean fromCart,
                                 BigDecimal goodsAmount, BigDecimal promotionDiscount, CouponUse couponUse,
                                 BigDecimal couponDiscount, int pointsUsed, BigDecimal pointsDiscount,
                                 BigDecimal freight, BigDecimal payAmount, int maxRedeemPoints,
                                 int customerPoints) {
    }
}
