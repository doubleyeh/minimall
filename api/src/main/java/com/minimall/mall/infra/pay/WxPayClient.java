package com.minimall.mall.infra.pay;

import com.minimall.mall.infra.auth.ClientTokenProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 微信支付网关封装(商城设计文档 3.3、3.8)。
 *
 * <p><b>当前是"接口已定、渠道未接"的状态</b>:本地与自动化测试用模拟实现,
 * 真实接入时替换 {@link #unifiedOrder} 与 {@link #verifyCallback} 的内部实现即可,
 * 上层的下单流程(落订单 → 锁库存 → 拉起支付 → 回调置为已支付)完全不用改。
 *
 * <p>这是刻意的:微信支付需要商户号、证书、APIv3 密钥,这些在开发环境里都没有,
 * 如果让它们成为整条链路的前置条件,那"下单 → 支付 → 发货"这条主流程就永远无法被测试覆盖。
 * 用 {@code minimall.mall.auth.wx-mock} 切换实现,生产环境保持 false。
 *
 * <p><b>真实接入时必须补的三件事</b>(现在的实现里标注为 TODO):
 * <ol>
 *   <li>统一下单改为调用 {@code /v3/pay/transactions/jsapi},用商户私钥签名</li>
 *   <li>回调必须验签并解密 {@code resource}(微信的回调是密文),不能被这里的明文 DTO 误导</li>
 *   <li>退款同理(见售后流程)</li>
 * </ol>
 */
@Component
public class WxPayClient {

    private static final Logger log = LoggerFactory.getLogger(WxPayClient.class);

    private final ClientTokenProperties properties;
    private final SecureRandom random = new SecureRandom();

    public WxPayClient(ClientTokenProperties properties) {
        this.properties = properties;
    }

    /**
     * 统一下单,返回预支付 ID 与小程序拉起支付所需的参数。
     *
     * <p><b>这是外部网络调用,必须在数据库事务之外执行</b>(3.3 的括注):
     * 放进事务会让数据库连接被网络超时一起拖住,高峰期会迅速耗尽连接池。
     */
    public Optional<PrepayResult> unifiedOrder(String outTradeNo, BigDecimal amount, String openid,
                                               String description) {
        if (properties.wxMockEnabled()) {
            String prepayId = "mock-prepay-" + outTradeNo;
            log.info("微信支付 mock 模式:统一下单 outTradeNo={} amount={} description={}",
                    outTradeNo, amount, description);
            return Optional.of(new PrepayResult(prepayId, mockPayParams(prepayId)));
        }
        // TODO 真实接入:POST /v3/pay/transactions/jsapi,使用商户私钥签名并校验响应签名
        log.warn("未配置真实微信支付渠道,统一下单失败 outTradeNo={}", outTradeNo);
        return Optional.empty();
    }

    /**
     * 校验回调来源。
     *
     * <p>真实实现要做两件事:验签(确认是微信发的)与解密({@code resource} 是密文)。
     * 这里在 mock 模式下直接放行,生产环境下**必须**替换 —— 不验签的回调接口等于
     * 任何人都能"通知"你把订单置为已支付。
     */
    public boolean verifyCallback(String signature, String body) {
        if (properties.wxMockEnabled()) {
            return true;
        }
        // TODO 真实接入:校验 Wechatpay-Signature 头 + 平台证书,并解密 resource
        return false;
    }

    /** 模拟拉起支付所需的参数(结构与 {@code wx.requestPayment} 一致)。 */
    private PrepayResult.PayParams mockPayParams(String prepayId) {
        return new PrepayResult.PayParams(
                String.valueOf(System.currentTimeMillis() / 1000),
                randomHex(16),
                "prepay_id=" + prepayId,
                "RSA",
                "mock-signature");
    }

    private String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    /** 统一下单结果。 */
    public record PrepayResult(String prepayId, PayParams payParams) {

        /** 与小程序 {@code wx.requestPayment} 参数一致。 */
        public record PayParams(String timeStamp, String nonceStr, String packageValue, String signType,
                                String paySign) {
        }
    }
}
