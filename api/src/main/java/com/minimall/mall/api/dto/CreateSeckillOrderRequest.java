package com.minimall.mall.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 秒杀下单请求(客户端)。
 *
 * <p>秒杀单不接收优惠券与积分:这两项在秒杀场景一律不参与(商城设计文档 3.12),
 * 让前端传进来再忽略,不如让接口本身就没有这两个字段。
 */
public record CreateSeckillOrderRequest(
        @NotNull Long addressId,
        @NotNull @Min(1) Integer quantity) {
}
