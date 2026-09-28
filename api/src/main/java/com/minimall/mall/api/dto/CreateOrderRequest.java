package com.minimall.mall.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建订单(商城设计文档 3.3)。
 *
 * @param items         直接购买的商品项。<b>为空时用购物车中已勾选的条目结算</b> ——
 *                      端上两条路径(立即购买 / 购物车结算)共用这一个接口
 * @param addressId     收货地址 ID(服务端会把它快照进订单,之后地址被改不影响历史订单)
 * @param couponRecordId 要使用的优惠券领取记录,可空
 * @param pointsToUse   本单要用的积分数,可空(空即不用)。**上限由服务端重算**,
 *                      端上传来的只是意向 —— 超过上限直接报错而不是静默夹取,
 *                      静默夹取会让"用户看到的价"与"实付"对不上
 * @param remark        买家留言
 */
public record CreateOrderRequest(
        List<Item> items,

        @NotNull(message = "请选择收货地址")
        Long addressId,

        Long couponRecordId,

        @Min(value = 0, message = "使用的积分不能为负数")
        Integer pointsToUse,

        @Size(max = 255, message = "买家留言不能超过 255 个字符")
        String remark) {

    public record Item(
            @NotNull(message = "SKU 不能为空") Long skuId,

            @NotNull(message = "数量不能为空")
            @Min(value = 1, message = "数量至少为 1")
            @Max(value = 999, message = "单个 SKU 最多购买 999 件")
            Integer quantity) {
    }
}
