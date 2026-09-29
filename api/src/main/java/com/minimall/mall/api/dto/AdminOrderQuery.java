package com.minimall.mall.api.dto;

import java.time.LocalDateTime;

/**
 * 管理端订单查询条件(列表与导出共用一份,口径不会走偏)。
 *
 * <p>{@code startTime}/{@code endTime} 过滤**下单时间**,两端都含。
 * 导出有行数上限,没有时间范围的导出在订单量上来以后必然撞上限 —— 所以这两个字段是导出能用的前提。
 */
public record AdminOrderQuery(String orderNo, Integer status, LocalDateTime startTime, LocalDateTime endTime) {
}
