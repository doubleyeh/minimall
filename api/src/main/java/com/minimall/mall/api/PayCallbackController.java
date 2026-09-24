package com.minimall.mall.api;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.api.dto.WxPayCallbackResponse;
import com.minimall.mall.service.PayService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 微信支付回调(商城设计文档 3.8):支付回调与退款回调都在这里。
 *
 * <p>几个必须清楚的点:
 * <ul>
 *   <li>路径 {@code /pay/callback/**} 在 4.9 的白名单里 —— 微信服务器当然没有登录态。
 *       "在白名单里"只表示允许没有租户上下文执行,**不代表免校验**:真正的校验是验签 + 金额比对</li>
 *   <li>租户编码在路径上而不是靠报文推断:微信回调体是密文,不知道租户就不知道用哪把密钥解密</li>
 *   <li>应答必须用微信定义的 {@code code=SUCCESS/FAIL},不能用项目自己的 {@code {code:0}},
 *       否则微信会一直重推。失败时返回非 2xx,让微信按失败处理</li>
 * </ul>
 */
@RestController
@RequestMapping("/pay/callback")
public class PayCallbackController {

    private final PayService payService;

    public PayCallbackController(PayService payService) {
        this.payService = payService;
    }

    @PostMapping("/wx/{tenantCode}")
    public ResponseEntity<WxPayCallbackResponse> payment(@PathVariable String tenantCode,
                                                         @RequestHeader("Wechatpay-Timestamp") String timestamp,
                                                         @RequestHeader("Wechatpay-Nonce") String nonce,
                                                         @RequestHeader("Wechatpay-Signature") String signature,
                                                         @RequestHeader("Wechatpay-Serial") String serial,
                                                         @RequestBody String rawBody) {
        try {
            payService.handlePayCallback(tenantCode, timestamp, nonce, serial, signature, rawBody);
            return ResponseEntity.ok(WxPayCallbackResponse.success());
        } catch (BusinessException ex) {
            return fail(ex);
        }
    }

    @PostMapping("/wx/{tenantCode}/refund")
    public ResponseEntity<WxPayCallbackResponse> refund(@PathVariable String tenantCode,
                                                        @RequestHeader("Wechatpay-Timestamp") String timestamp,
                                                        @RequestHeader("Wechatpay-Nonce") String nonce,
                                                        @RequestHeader("Wechatpay-Signature") String signature,
                                                        @RequestHeader("Wechatpay-Serial") String serial,
                                                        @RequestBody String rawBody) {
        try {
            payService.handleRefundCallback(tenantCode, timestamp, nonce, serial, signature, rawBody);
            return ResponseEntity.ok(WxPayCallbackResponse.success());
        } catch (BusinessException ex) {
            return fail(ex);
        }
    }

    /**
     * 业务异常映射成微信要求的失败应答。
     *
     * <p>这里必须自己 catch:走全局处理器的话会被翻译成 HTTP 200 + 业务码,微信看到的就成了"成功"。
     */
    private ResponseEntity<WxPayCallbackResponse> fail(BusinessException ex) {
        ErrorCode code = ex.getErrorCode() == null ? ErrorCode.BUSINESS_ERROR : ex.getErrorCode();
        HttpStatus status = switch (code) {
            case NOT_FOUND, TENANT_ABNORMAL -> HttpStatus.NOT_FOUND;
            case PAY_SIGNATURE_INVALID -> HttpStatus.UNAUTHORIZED;
            case WX_PAY_NOTIFY_INVALID, PAY_AMOUNT_INVALID, PAY_CHANNEL_NOT_CONFIGURED -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(WxPayCallbackResponse.fail(ex.getMessage()));
    }
}
