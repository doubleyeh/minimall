package com.minimall.mall.api;

import com.minimall.common.ApiResponse;
import com.minimall.mall.service.PayService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 微信支付回调(商城设计文档 3.8)。
 *
 * <p>几个必须清楚的点:
 * <ul>
 *   <li>路径 {@code /pay/callback/**} 在 4.9 的白名单里 —— 微信服务器当然没有登录态。
 *       "在白名单里"只表示允许没有租户上下文执行,**不代表免校验**:真正的校验是验签 + 金额比对</li>
 *   <li>回调可能**重复推送**,幂等由 {@code handlePayCallback} 内部保证(按支付流水状态短路)</li>
 *   <li>请求体格式与真实微信不同(真实回调是密文,需要平台证书解密)。这里定义的是内部 DTO,
 *       接入真实渠道时把解析与验签换成官方 SDK 的实现即可,业务处理不用动</li>
 * </ul>
 */
@RestController
@RequestMapping("/pay/callback")
public class PayCallbackController {

    private final PayService payService;

    public PayCallbackController(PayService payService) {
        this.payService = payService;
    }

    @PostMapping("/wx")
    public ApiResponse<Void> wxPayCallback(@RequestBody WxPayCallbackRequest request) {
        payService.handlePayCallback(request.outTradeNo(), request.transactionId(),
                request.amount(), request.success(), request.rawBody());
        // 无论业务侧如何处理都要返回成功:返回失败会让微信持续重推,
        // 而"找不到订单"这类问题重推一百次也不会自己好,只会掩盖真正的原因(日志里有记录)
        return ApiResponse.ok();
    }

    /**
     * 支付回调请求体(内部结构,非微信原始格式)。
     *
     * @param amount  回调金额(元);与支付流水金额不一致时整笔拒绝,防止被篡改
     * @param success 是否支付成功;false 表示支付失败/关闭
     */
    public record WxPayCallbackRequest(
            String outTradeNo,
            String transactionId,
            BigDecimal amount,
            boolean success,
            String rawBody) {
    }
}
