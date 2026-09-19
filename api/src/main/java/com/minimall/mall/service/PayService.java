package com.minimall.mall.service;

import com.minimall.mall.api.dto.OrderCreateResponse;

import java.math.BigDecimal;

/**
 * 支付(商城设计文档 3.3、3.8)。
 */
public interface PayService {

    /**
     * 拉起支付:创建支付流水并调用统一下单。
     *
     * <p>网络调用在事务之外(见实现类说明)。
     */
    OrderCreateResponse.PayParams prepay(Long orderId);

    /**
     * 支付结果回调(3.8)。
     *
     * <p>回调来自微信服务器,**没有租户上下文**:实现里先用超管上下文按商户订单号定位支付流水,
     * 拿到 tenantId 后再切回该租户继续处理。
     *
     * @param amount 回调金额(分转元后与支付流水比对,不一致直接拒绝)
     */
    void handlePayCallback(String outTradeNo, String wxTransactionId, BigDecimal amount, boolean success,
                           String rawBody);
}
