package com.minimall.mall.api.dto;

import jakarta.validation.constraints.Min;

/**
 * 秒杀结算试算请求(客户端)。
 *
 * @param addressId 可空:还没选地址时只算商品金额,运费按 0 计并返回 needAddress
 */
public record SeckillPreviewRequest(Long addressId, @Min(1) Integer quantity) {
}
