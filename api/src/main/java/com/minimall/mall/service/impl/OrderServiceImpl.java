package com.minimall.mall.service.impl;

import com.minimall.api.mall.dto.ClientOrderView;
import com.minimall.api.mall.dto.CreateOrderRequest;
import com.minimall.api.mall.dto.OrderCreateResponse;
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
import com.minimall.mall.service.OrderService;
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
        this.numberGenerator = numberGenerator;
    }

    @Override
    public OrderCreateResponse create(CreateOrderRequest request) {
        Long customerId = ClientContext.requireCustomerId();
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        boolean fromCart = request.items() == null || request.items().isEmpty();
        List<Line> lines = fromCart ? linesFromCart(customerId) : linesFromRequest(request.items(), customerId);
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "没有可结算的商品");
        }

        MallCustomerAddress address = addressRepository.findById(request.addressId())
                .filter(item -> Objects.equals(item.getCustomerId(), customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "收货地址不存在"));

        BigDecimal goodsAmount = lines.stream().map(Line::totalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ① 满减(3.5):同一订单只取减免最大的一个活动
        OrderAmountCalculator.PromotionHit promotion = bestPromotion(lines, goodsAmount);
        BigDecimal promotionDiscount = promotion == null ? BigDecimal.ZERO : promotion.discount();
        BigDecimal amountAfterPromotion = goodsAmount.subtract(promotionDiscount);

        // ② 优惠券:门槛按"满减后的商品金额"判断(3.5)
        CouponUse couponUse = resolveCoupon(request.couponRecordId(), customerId, amountAfterPromotion);
        BigDecimal couponDiscount = couponUse.discount();

        // ③ 运费(3.7):按商品金额与数量/重量分组计算
        BigDecimal freight = freightAmount(lines, address.getProvince());

        BigDecimal payAmount = calculator.payable(goodsAmount, promotionDiscount, couponDiscount, freight);

        MallOrder order = new MallOrder();
        order.setOrderNo(numberGenerator.nextOrderNo());
        order.setCustomerId(customerId);
        order.setStatus(MallOrder.STATUS_PENDING_PAY);
        order.setGoodsAmount(calculator.money(goodsAmount));
        order.setFreightAmount(calculator.money(freight));
        order.setPromotionDiscountAmount(calculator.money(promotionDiscount));
        order.setCouponDiscountAmount(calculator.money(couponDiscount));
        order.setPayAmount(payAmount);
        order.setCouponRecordId(couponUse.recordId());
        order.setReceiverName(address.getReceiverName());
        order.setReceiverPhone(address.getReceiverPhone());
        order.setReceiverAddress(address.getProvince() + address.getCity() + address.getDistrict()
                + address.getDetailAddress());
        order.setRemark(request.remark());
        order = orderRepository.save(order);

        for (Line line : lines) {
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

        if (couponUse.recordId() != null) {
            int used = couponRecordRepository.useForOrder(couponUse.recordId(), customerId, tenantId,
                    order.getId(), LocalDateTime.now());
            if (used == 0) {
                // 券在"校验"与"核销"之间被别的订单用掉了(同一张券开了两个结算页)
                throw new BusinessException(ErrorCode.DATA_CONFLICT, "优惠券已被使用,请重新选择");
            }
        }

        if (fromCart) {
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
        payment.setPayAmount(payAmount);
        payment.setPayStatus(MallWxPayment.PAY_STATUS_PENDING);
        paymentRepository.save(payment);

        log.info("订单创建成功 tenantId={} customerId={} orderNo={} payAmount={}",
                tenantId, customerId, order.getOrderNo(), payAmount);

        return new OrderCreateResponse(order.getId(), order.getOrderNo(),
                order.getGoodsAmount(), order.getFreightAmount(), order.getPromotionDiscountAmount(),
                order.getCouponDiscountAmount(), order.getPayAmount(), null);
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
        MallOrder order = loadOwned(orderId, customerId);
        if (order.getStatus() != MallOrder.STATUS_PENDING_RECEIVE) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只有待收货的订单可以确认收货");
        }
        int from = order.getStatus();
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(MallOrder.STATUS_FINISHED);
        order.setReceiveTime(now);
        order.setFinishTime(now);
        writeStatusLog(order.getId(), from, MallOrder.STATUS_FINISHED, OPERATOR_BUYER, customerId, "确认收货");
        // 积分/成长值的增加按 3.4 应在"确认收货"时触发,但积分规则(比例、是否分商品)尚未确定,
        // 不做半实现 —— 半实现的积分会在规则明确后变成需要人工修正的历史数据(见文档开放项)
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
        List<MallOrder> orders = orderRepository.findAutoReceiveOrders(MallOrder.STATUS_PENDING_RECEIVE,
                shippedBefore, PageRequest.of(0, BATCH_SIZE));
        LocalDateTime now = LocalDateTime.now();
        for (MallOrder order : orders) {
            int from = order.getStatus();
            order.setStatus(MallOrder.STATUS_FINISHED);
            order.setReceiveTime(now);
            order.setFinishTime(now);
            writeStatusLog(order.getId(), from, MallOrder.STATUS_FINISHED, OPERATOR_SYSTEM, null, "发货后超时,系统自动确认收货");
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

        // 顺序很关键:**先改实体的状态,再做批量更新**。
        // 下面那些库存/优惠券更新用的是 @Modifying(clearAutomatically = true),
        // 它们会 flush 之后**清空持久化上下文** —— 清空之后 order 就成了游离对象,
        // 此时再 setStatus 只是改一个普通 Java 对象,事务提交时不会被持久化(表现为"取消了但状态没变")。
        // 反过来先改实体,则会在下一次 flush 时一并写库,顺序天然正确。
        order.setStatus(toStatus);
        order.setCloseReason(closeReason);
        order.setCancelTime(LocalDateTime.now());

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
                order.getCouponDiscountAmount(), order.getPayAmount(), order.getReceiverName(),
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
}
