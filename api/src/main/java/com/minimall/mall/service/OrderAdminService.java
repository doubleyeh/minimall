package com.minimall.mall.service;

import com.minimall.api.mall.dto.AdminOrderView;
import com.minimall.common.PageResult;

/**
 * 商家管理端订单(商城设计文档 3.4)。
 *
 * <p>与端上订单服务的区别:这里按**租户**维度看全部订单(而不是只看自己的),
 * 数据范围由租户过滤器保证 —— 商家只能看到自己租户的订单。权限由 {@code mall:order:*} 控制。
 */
public interface OrderAdminService {

    PageResult<AdminOrderView> page(String orderNo, Integer status, int pageNo, int pageSize);

    AdminOrderView detail(Long orderId);

    /** 发货:status 2 → 3,并记录物流单号。 */
    void ship(Long orderId, String logisticsCompany, String logisticsNo);

    /**
     * 商家取消(仅待发货订单):3.4 规定需先协商退款,所以这里会**同时创建退款记录并回补库存**。
     *
     * @param reason 取消原因,记入订单状态流水
     */
    void cancel(Long orderId, String reason);
}
