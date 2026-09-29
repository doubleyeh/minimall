package com.minimall.mall.service;

import com.minimall.mall.api.dto.OrderCreateResponse;

import java.time.LocalDateTime;
import java.util.List;

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

    /** 结算 0 元订单(3.3):满减/券把实付打到 0 时不需要走支付渠道,但要立刻置为已支付。
     *
     * <p>只对 `payAmount <= 0` 的订单生效,其余直接返回。幂等。
     */
    void settleFreeOrder(Long orderId);

    /** 退款结果回调(3.8):定位退款流水并落最终状态。 */
    void handleRefundCallback(String tenantCode, String timestamp, String nonce, String serial,
                              String signature, String rawBody);

    /**
     * 查单确认已支付后,把"已关闭"的订单置回待发货(3.8)。
     *
     * <p>订单被关闭时锁定库存已释放,所以这里只扣实际库存。订单已不在"已关闭"状态、或支付流水已是成功时直接返回(幂等)。
     */
    void settleClosedPaidOrder(Long orderId, String wxTransactionId, String remark);

    /**
     * 定时查单的候选:最近关闭、且支付流水仍是待支付的订单(3.8)。
     *
     * <p>只取已经拉起过支付(有 prepay_id)的 —— 没拉起过就不可能付过,查单纯属浪费调用。
     */
    List<ClosedUnpaidOrder> listRecentlyClosedUnpaid(LocalDateTime closedAfter);

    /** 查单候选:定位微信支付需要的几个值。 */
    record ClosedUnpaidOrder(Long tenantId, Long orderId, String outTradeNo) {
    }
}
