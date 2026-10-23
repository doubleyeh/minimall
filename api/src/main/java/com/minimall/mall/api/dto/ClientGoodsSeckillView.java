package com.minimall.mall.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品详情页上的秒杀入口(客户端)。
 *
 * <p>只展示"有秒杀"这件事与最低限度的时间/价格信息,完整的配额、进度、抢购在秒杀详情页里。
 * 没有秒杀时整个字段为 null —— 端上据此决定是否渲染标签。
 */
public record ClientGoodsSeckillView(
        Long itemId,
        Long skuId,
        BigDecimal seckillPrice,
        Integer remaining,
        Integer perCustomerLimit,
        LocalDateTime startTime,
        LocalDateTime endTime,
        Integer status) {
}
