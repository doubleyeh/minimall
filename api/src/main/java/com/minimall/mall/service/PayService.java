package com.minimall.mall.service;

import com.minimall.mall.api.dto.OrderCreateResponse;

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
     * <p>回调来自微信服务器,没有租户上下文,所以租户编码直接由回调路径给出:
     * 微信的回调体是密文,不先知道租户就不知道用哪把 APIv3 密钥解密。
     *
     * <p>实现里先验签再解密,然后按租户定位支付流水;金额不符、验签失败都抛
     * {@code BusinessException},由 controller 映射成微信要求的失败应答。
     */
    void handlePayCallback(String tenantCode, String timestamp, String nonce, String serial,
                           String signature, String rawBody);

    /** 退款结果回调(3.8):定位退款流水并落最终状态。 */
    void handleRefundCallback(String tenantCode, String timestamp, String nonce, String serial,
                              String signature, String rawBody);
}
