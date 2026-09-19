package com.minimall.mall.service;

import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.api.dto.CreateOrderRequest;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.MallCustomerAddress;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallCartRepository;
import com.minimall.mall.domain.repository.MallCustomerAddressRepository;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallGoodsReviewRepository;
import com.minimall.mall.domain.repository.MallOrderItemRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.domain.repository.MallStockLogRepository;
import com.minimall.mall.domain.repository.MallWxPaymentRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.infra.auth.ClientPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * 商城服务集成测试的公共夹具。
 *
 * <p>为什么要有这个基类:客户端侧的每个服务(购物车、地址、评价、下单)都需要同一套前置数据
 * —— 一个客户、一个可售商品与 SKU、一条收货地址 —— 以及同一套"把租户/客户端上下文摆好"的样板代码。
 * 各测试类各抄一遍的话,样板会占掉半个文件,真正的断言反而被淹掉。
 *
 * <p>两套上下文必须区分清楚,这是客户端侧测试最容易踩的坑:
 * <ul>
 *   <li>{@link #inTenant} —— 只设租户,用于**准备数据**与**断言落库结果**(读写仓储时租户过滤器要生效)</li>
 *   <li>{@link #asClient} —— 额外设 {@code ClientContext}(客户身份),用于**调用服务方法**:
 *       客户端服务的第一行几乎都是 {@code ClientContext.requireCustomerId()},
 *       不设就会抛"未登录",而且报错信息与真正的业务失败很难区分</li>
 * </ul>
 *
 * <p>清理放在 {@code @AfterEach}:这些表没有外键约束(架构文档 5.6),留下的孤儿数据会影响
 * 后续用例的计数类断言(比如"加购累加"如果上次的条目还在,数量就对不上)。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("dev")
abstract class MallClientServiceTestBase {

    /** 种子数据里的平台租户。 */
    protected static final long TENANT_ID = 1L;

    @Autowired
    protected MallCustomerRepository customerRepository;
    @Autowired
    protected MallCustomerAddressRepository addressRepository;
    @Autowired
    protected MallGoodsRepository goodsRepository;
    @Autowired
    protected MallSkuRepository skuRepository;
    @Autowired
    protected MallCartRepository cartRepository;
    @Autowired
    protected MallGoodsReviewRepository reviewRepository;
    @Autowired
    protected MallOrderRepository orderRepository;
    @Autowired
    protected MallOrderItemRepository orderItemRepository;
    @Autowired
    protected MallWxPaymentRepository paymentRepository;
    @Autowired
    protected MallStockLogRepository stockLogRepository;
    /** 下单入口本身也是夹具的一部分:评价、售后、定时任务都要先有一个真实订单。 */
    @Autowired
    protected OrderService orderService;
    /** 用于调整 {@code create_time}/{@code ship_time} 这类业务代码不允许改的列(模拟时间流逝)。 */
    @Autowired
    protected JdbcTemplate jdbcTemplate;

    /** 主客户:大多数用例的操作者。 */
    protected Long customerId;
    /** 另一个客户:专门用来验"别人的数据动不了"。 */
    protected Long otherCustomerId;

    protected Long goodsId;
    protected Long skuId;
    protected Long addressId;

    @BeforeEach
    void setUpBaseFixture() {
        inTenant(() -> {
            customerId = newCustomer("it-c-" + System.nanoTime());
            otherCustomerId = newCustomer("it-o-" + System.nanoTime());

            MallGoods goods = new MallGoods();
            goods.setCategoryId(0L);
            goods.setGoodsName("集成测试商品");
            goods.setMainImage("https://example.com/it.png");
            goods.setSalePriceMin(new BigDecimal("50.00"));
            goods.setSalePriceMax(new BigDecimal("50.00"));
            goods.setTotalStock(10);
            goods.setSaleCount(0);
            goods.setStatus(1);
            goods.setSortOrder(0);
            goodsId = goodsRepository.save(goods).getId();

            MallSku sku = new MallSku();
            sku.setGoodsId(goodsId);
            sku.setSkuCode("IT-SKU-" + System.nanoTime());
            sku.setSkuName("默认规格");
            sku.setPrice(new BigDecimal("50.00"));
            sku.setStock(10);
            sku.setLockedStock(0);
            sku.setStatus(1);
            skuId = skuRepository.save(sku).getId();

            addressId = newAddress(customerId, "测试收货人");
            return null;
        });
    }

    @AfterEach
    void tearDownBaseFixture() {
        ClientContext.clear();
        inTenant(() -> {
            for (Long id : new Long[] { customerId, otherCustomerId }) {
                if (id == null) {
                    continue;
                }
                cartRepository.deleteAll(cartRepository.findByCustomerIdOrderByIdDesc(id));
                reviewRepository.findAll().stream()
                        .filter(review -> id.equals(review.getCustomerId()))
                        .forEach(reviewRepository::delete);
                orderRepository.findAll().stream()
                        .filter(order -> id.equals(order.getCustomerId()))
                        .forEach(order -> {
                            orderItemRepository.findByOrderIdOrderByIdAsc(order.getId())
                                    .forEach(orderItemRepository::delete);
                            paymentRepository.findFirstByOrderIdOrderByIdDesc(order.getId())
                                    .ifPresent(paymentRepository::delete);
                            orderRepository.delete(order);
                        });
                addressRepository.findByCustomerIdOrderByIsDefaultDescIdDesc(id)
                        .forEach(addressRepository::delete);
                customerRepository.findById(id).ifPresent(customerRepository::delete);
            }
            if (goodsId != null) {
                stockLogRepository.findAll().stream()
                        .filter(log -> skuId.equals(log.getSkuId()))
                        .forEach(stockLogRepository::delete);
                skuRepository.findById(skuId).ifPresent(skuRepository::delete);
                goodsRepository.findById(goodsId).ifPresent(goodsRepository::delete);
            }
            return null;
        });
        TenantContext.clear();
    }

    // ---------------------------------------------------------------- 上下文

    /** 以"该租户下的系统身份"执行(准备数据、断言落库)。 */
    protected <T> T inTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, false, () -> {
            AuditContext.bind(new AuditContext(TENANT_ID, customerId, "127.0.0.1", "mall-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    /**
     * 以某个**客户**的身份调用服务。
     *
     * <p>真实请求里这两个上下文由 {@code ClientAuthFilter} 一起设置;直接调 service 时必须自己摆好,
     * 否则 {@code ClientContext.requireCustomerId()} 会抛异常 —— 那个报错与业务失败长得不一样但很难区分。
     */
    protected <T> T asClient(Long actingCustomerId, Supplier<T> action) {
        // 用 callAs / callAsTenant 而不是手动 set 后再 clear:两者都会把**上一层**上下文还原,
        // 于是夹具可以嵌套调用(典型场景:在外层 asClient 里调 createOrder,而它自己也要以客户身份下单)。
        // 手动 clear 的版本在内层返回后会把外层身份一并清掉,表现是"明明设了身份却报未登录"——
        // 这个坑第一次就踩到了,报错信息看起来像认证问题,其实是测试夹具的作用域写错。
        return ClientContext.callAs(new ClientPrincipal(TENANT_ID, actingCustomerId),
                () -> TenantContext.callAsTenant(TENANT_ID, false, action));
    }

    protected void asClientRun(Long actingCustomerId, Runnable action) {
        asClient(actingCustomerId, () -> {
            action.run();
            return null;
        });
    }

    // ---------------------------------------------------------------- 造数据

    protected Long newCustomer(String openid) {
        MallCustomer customer = new MallCustomer();
        customer.setOpenid(openid);
        customer.setStatus(1);
        customer.setPoints(0);
        customer.setGrowthValue(0);
        customer.setRegisterTime(LocalDateTime.now());
        return customerRepository.save(customer).getId();
    }

    protected Long newAddress(Long ownerCustomerId, String receiverName) {
        MallCustomerAddress address = new MallCustomerAddress();
        address.setCustomerId(ownerCustomerId);
        address.setReceiverName(receiverName);
        address.setReceiverPhone("13800000000");
        address.setProvince("广东省");
        address.setCity("深圳市");
        address.setDistrict("南山区");
        address.setDetailAddress("测试路 1 号");
        address.setIsDefault(1);
        return addressRepository.save(address).getId();
    }

    /**
     * 走真实下单入口建一个订单(而不是直接插表):下单七步里的算价、锁库存、建支付单
     * 都是后续用例的前置条件,绕过去会让后面的断言建立在一个不真实的状态上。
     */
    protected OrderCreateResponse createOrder(Long ownerCustomerId, Long ownerAddressId, int quantity) {
        return asClient(ownerCustomerId, () -> orderService.create(
                new CreateOrderRequest(
                        java.util.List.of(new CreateOrderRequest.Item(skuId, quantity)),
                        ownerAddressId, null, null)));
    }

    /** 把订单直接推到指定状态(用于"订单已处于某状态之后"的用例)。 */
    protected void forceOrderStatus(Long orderId, int status) {
        inTenant(() -> {
            MallOrder order = orderRepository.findById(orderId).orElseThrow();
            order.setStatus(status);
            if (status == MallOrder.STATUS_FINISHED) {
                order.setFinishTime(LocalDateTime.now());
            }
            orderRepository.save(order);
            return null;
        });
    }
}
