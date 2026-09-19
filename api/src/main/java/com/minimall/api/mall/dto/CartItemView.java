package com.minimall.api.mall.dto;

import java.math.BigDecimal;

/**
 * 购物车条目(端上)。
 *
 * <p>{@code valid = false} 表示这个条目**当前不可结算**(商品下架或已售罄)。
 * 刻意不把它从列表里删掉:用户记得自己加过它,直接消失会让人以为购物车"丢了东西";
 * 置灰并给出原因才是正常做法(结算时也会跳过或拒绝这些条目)。
 *
 * @param availableStock 可售库存,端上据此提示"仅剩 N 件"
 */
public record CartItemView(
        Long id,
        Long skuId,
        Long goodsId,
        String goodsName,
        String skuName,
        String image,
        BigDecimal price,
        Integer availableStock,
        Integer quantity,
        Integer selected,
        boolean valid) {
}
