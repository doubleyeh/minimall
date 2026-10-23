package com.minimall.mall.api.dto;

import java.math.BigDecimal;

/**
 * 秒杀结算试算(客户端)。
 *
 * <p>与普通结算一致:**端上不做任何金额运算**,实付由服务端算。秒杀单只有一件商品、
 * 不参与满减/券/积分,所以这里只有"商品金额 + 运费"。
 *
 * @param needAddress 还没有收货地址时为 true,端上要引导去新增
 */
public record SeckillPreviewView(
        Long itemId,
        Long goodsId,
        String goodsName,
        String goodsImage,
        Long skuId,
        String skuName,
        BigDecimal seckillPrice,
        Integer quantity,
        BigDecimal goodsAmount,
        BigDecimal freightAmount,
        BigDecimal payAmount,
        Integer remaining,
        boolean needAddress) {
}
