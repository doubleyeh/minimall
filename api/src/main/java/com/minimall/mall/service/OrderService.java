package com.minimall.mall.service;

import com.minimall.mall.api.dto.ClientOrderView;
import com.minimall.mall.api.dto.CreateOrderRequest;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.common.PageResult;

import java.time.LocalDateTime;

/**
 * 订单(商城设计文档 3.3、3.4)。
 *
 * <p>状态机、库存锁定、优惠核销三件事必须落在**同一个事务**里:
 * 只落单不锁库存会超卖;锁了库存但订单没落成会永远占着货;券核销与订单不同事务会丢券。
 * 所以这些动作只允许从这个服务发生,不要在别处拼装。
 */
public interface OrderService {

    /**
     * 下单(3.3 的七步)。
     *
     * <p>微信统一下单的**网络调用不在这里**,由 {@link PayService} 在事务提交后发起 ——
     * 外部网络调用进事务会让数据库连接被网络超时拖住(3.3 的括注)。
     */
    OrderCreateResponse create(CreateOrderRequest request);

    /** 买家取消(仅待支付订单)。释放锁定库存并退回优惠券。 */
    void cancel(Long orderId);

    /** 确认收货:status 3 → 4。 */
    void confirmReceive(Long orderId);

    PageResult<ClientOrderView> list(Integer status, int pageNo, int pageSize);

    ClientOrderView detail(Long orderId);

    /**
     * 关闭超时未支付的订单(3.4 的每分钟任务)。
     *
     * @return 实际关闭的订单数
     */
    int closeTimeoutOrders(LocalDateTime createdBefore);

    /**
     * 自动确认收货(3.4 的每小时任务)。
     *
     * @return 实际完成的订单数
     */
    int autoReceiveOrders(LocalDateTime shippedBefore);
}
