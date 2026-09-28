package com.minimall.mall.api.dto;

import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * 结算试算(商城设计文档 3.11):**只算不落单**,不占库存、不核销券、不扣积分。
 *
 * <p>存在的理由:结算页要把五种金额都展示出来,而运费(模板/区域/包邮)、满减(活动+范围+阶梯)、
 * 券门槛、积分上限全在服务端。端上自己算必然与真实下单漂移,而漂移的方向通常是**少收钱**。
 * 所以试算与下单走同一段编排。
 *
 * @param addressId   可空:还没选地址时也允许试算,此时运费按 0 计并置 {@code needAddress}
 * @param pointsToUse 端上想用的积分数,用来算"用了之后实付多少";不传则按不用积分算
 */
public record OrderPreviewRequest(
        List<CreateOrderRequest.Item> items,

        Long addressId,

        Long couponRecordId,

        @Min(value = 0, message = "使用的积分不能为负数")
        Integer pointsToUse) {
}
