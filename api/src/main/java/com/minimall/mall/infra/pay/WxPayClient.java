package com.minimall.mall.infra.pay;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.tenant.TenantLookup;
import com.minimall.infra.tenant.TenantSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 微信支付 V3 网关封装(商城设计文档 3.3、3.8)。
 *
 * <p>两个出网方法都在数据库事务之外(3.3);失败一律返回空,由上层翻译成业务错误 ——
 * 微信的报错文案不直接外抛。
 *
 * <p>签名用的是**实际发送的那份 JSON 原文**:字段顺序或空格一变签名就对不上,
 * 所以先拼好字符串再签名,而不是序列化一遍再签。
 */
@Component
public class WxPayClient {

    private static final Logger log = LoggerFactory.getLogger(WxPayClient.class);

    private static final String BASE_URL = "https://api.mch.weixin.qq.com";
    private static final String JSAPI_PATH = "/v3/pay/transactions/jsapi";
    private static final String REFUND_PATH = "/v3/refund/domestic/refunds";

    private final WxPayConfigProvider configProvider;
    private final WxPayHttpClient httpClient;
    private final WxPayProperties properties;
    private final TenantLookup tenantLookup;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public WxPayClient(WxPayConfigProvider configProvider, WxPayHttpClient httpClient,
                       WxPayProperties properties, TenantLookup tenantLookup) {
        this.configProvider = configProvider;
        this.httpClient = httpClient;
        this.properties = properties;
        this.tenantLookup = tenantLookup;
    }

    /**
     * 回调的验签与解密。
     *
     * <p>顺序固定为**先验签后解密**:反过来的话等于拿 APIv3 密钥去解一份来源未证实的数据。
     */
    public WxPayNotify verifyAndDecrypt(String tenantCode, String timestamp, String nonce, String serial,
                                        String signature, String rawBody) {
        TenantSnapshot tenant = tenantLookup.byCode(tenantCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "租户不存在"));
        WxPayCredentials credentials = configProvider.byTenantId(tenant.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAY_CHANNEL_NOT_CONFIGURED, "该商户未配置微信支付"));

        if (Math.abs(Instant.now().getEpochSecond() - parseTimestamp(timestamp))
                > WxPayCrypto.TIMESTAMP_TOLERANCE_SECONDS) {
            log.warn("回调时间戳超出窗口 tenantCode={} timestamp={}", tenantCode, timestamp);
            throw new BusinessException(ErrorCode.PAY_SIGNATURE_INVALID, "回调时间戳超窗");
        }
        if (!hasText(credentials.platformSerialNo()) || !credentials.platformSerialNo().equals(serial)) {
            throw new BusinessException(ErrorCode.PAY_SIGNATURE_INVALID, "平台证书序列号不匹配");
        }
        if (!WxPayCrypto.verify(WxPayCrypto.callbackSignString(timestamp, nonce, rawBody), signature,
                credentials.platformPublicKey())) {
            throw new BusinessException(ErrorCode.PAY_SIGNATURE_INVALID, "回调签名校验失败");
        }

        JsonNode root = readTree(rawBody);
        JsonNode resource = root == null ? null : root.get("resource");
        if (resource == null) {
            throw new BusinessException(ErrorCode.WX_PAY_NOTIFY_INVALID, "回调缺少 resource");
        }
        String plain = WxPayCrypto.decryptResource(credentials.apiV3Key(),
                text(resource, "ciphertext"), text(resource, "nonce"), text(resource, "associated_data"));
        JsonNode decrypted = readTree(plain);
        if (decrypted == null) {
            throw new BusinessException(ErrorCode.WX_PAY_NOTIFY_INVALID, "回调 resource 解密失败");
        }
        return new WxPayNotify(tenant.id(), text(root, "event_type"), decrypted);
    }

    /** JSAPI 统一下单,返回预支付 ID 与小程序拉起支付的参数。 */
    public Optional<PrepayResult> unifiedOrder(WxPayOrderCommand command) {
        WxPayCredentials credentials = credentialsOf(command.tenantId());
        String body = writeJson(jsapiBody(command, credentials));
        String timestamp = nowSeconds();
        String nonce = randomHex(16);

        WxPayHttpClient.WxPayHttpResult result = httpClient.post(BASE_URL + JSAPI_PATH,
                signedHeaders("POST", JSAPI_PATH, body, credentials, timestamp, nonce), body);
        if (result.status() != 200) {
            log.warn("微信统一下单失败 outTradeNo={} status={} body={}",
                    command.outTradeNo(), result.status(), result.body());
            return Optional.empty();
        }
        String prepayId = textOf(result.body(), "prepay_id");
        if (prepayId == null) {
            log.warn("微信统一下单未返回 prepay_id outTradeNo={}", command.outTradeNo());
            return Optional.empty();
        }
        return Optional.of(new PrepayResult(prepayId, payParams(prepayId, credentials)));
    }

    /**
     * 提交退款申请,返回微信退款单号。
     *
     * <p>微信退款是异步的:这里只负责提交,最终成败由退款回调更新。
     */
    public Optional<String> refund(WxPayRefundCommand command) {
        WxPayCredentials credentials = credentialsOf(command.tenantId());
        String body = writeJson(refundBody(command, credentials));
        String timestamp = nowSeconds();
        String nonce = randomHex(16);

        WxPayHttpClient.WxPayHttpResult result = httpClient.post(BASE_URL + REFUND_PATH,
                signedHeaders("POST", REFUND_PATH, body, credentials, timestamp, nonce), body);
        if (result.status() != 200) {
            log.warn("微信退款申请失败 outRefundNo={} status={} body={}",
                    command.outRefundNo(), result.status(), result.body());
            return Optional.empty();
        }
        String refundId = textOf(result.body(), "refund_id");
        if (refundId == null) {
            log.warn("微信退款未返回 refund_id outRefundNo={}", command.outRefundNo());
            return Optional.empty();
        }
        return Optional.of(refundId);
    }

    /** direct 用 appid/mchid,partner 用 sp_appid/sp_mchid/sub_appid/sub_mchid。 */
    private Map<String, Object> jsapiBody(WxPayOrderCommand command, WxPayCredentials credentials) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (credentials.partner()) {
            body.put("sp_appid", credentials.appId());
            body.put("sp_mchid", credentials.mchId());
            if (hasText(credentials.subAppId())) {
                body.put("sub_appid", credentials.subAppId());
            }
            if (hasText(credentials.subMchId())) {
                body.put("sub_mchid", credentials.subMchId());
            }
        } else {
            body.put("appid", credentials.appId());
            body.put("mchid", credentials.mchId());
        }
        body.put("description", command.description());
        body.put("out_trade_no", command.outTradeNo());
        body.put("notify_url", properties.notifyUrl(command.tenantCode(), false));
        body.put("amount", Map.of("total", WxPayAmounts.toCents(command.amount()), "currency", "CNY"));

        // openid 属于哪个 appid 决定了用哪个字段:sub_appid 配了就取该小程序下的 openid
        String payerKey = !credentials.partner() ? "openid"
                : hasText(credentials.subAppId()) ? "sub_openid" : "sp_openid";
        body.put("payer", Map.of(payerKey, command.openid()));
        return body;
    }

    /** 优先用 transaction_id;partner 模式要带 sub_mchid。 */
    private Map<String, Object> refundBody(WxPayRefundCommand command, WxPayCredentials credentials) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (hasText(command.transactionId())) {
            body.put("transaction_id", command.transactionId());
        } else {
            body.put("out_trade_no", command.outTradeNo());
        }
        body.put("out_refund_no", command.outRefundNo());
        body.put("reason", command.reason() == null ? "退款" : command.reason());
        body.put("notify_url", properties.notifyUrl(command.tenantCode(), true));
        body.put("amount", Map.of(
                "refund", WxPayAmounts.toCents(command.refundAmount()),
                "total", WxPayAmounts.toCents(command.totalAmount()),
                "currency", "CNY"));
        if (credentials.partner() && hasText(credentials.subMchId())) {
            body.put("sub_mchid", credentials.subMchId());
        }
        return body;
    }

    private Map<String, String> signedHeaders(String method, String path, String body,
                                              WxPayCredentials credentials, String timestamp, String nonce) {
        String signature = WxPayCrypto.sign(
                WxPayCrypto.requestSignString(method, path, timestamp, nonce, body),
                credentials.merchantPrivateKey());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", WxPayCrypto.authorizationHeader(
                credentials.mchId(), nonce, signature, timestamp, credentials.merchantSerialNo()));
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "mini-mall");
        return headers;
    }

    /** 小程序 wx.requestPayment 的参数;paySign 的 appId 必须与 openid 同主体。 */
    private PrepayResult.PayParams payParams(String prepayId, WxPayCredentials credentials) {
        String timeStamp = nowSeconds();
        String nonceStr = randomHex(16);
        String packageValue = "prepay_id=" + prepayId;
        String source = credentials.payerAppId() + "\n" + timeStamp + "\n" + nonceStr + "\n" + packageValue + "\n";
        return new PrepayResult.PayParams(timeStamp, nonceStr, packageValue, "RSA",
                WxPayCrypto.sign(source, credentials.merchantPrivateKey()));
    }

    private WxPayCredentials credentialsOf(Long tenantId) {
        if (!properties.notifyBaseUrlConfigured()) {
            throw new BusinessException(ErrorCode.PAY_CHANNEL_NOT_CONFIGURED, "未配置回调地址前缀");
        }
        return configProvider.byTenantId(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAY_CHANNEL_NOT_CONFIGURED, "该商户未配置微信支付"));
    }

    private String writeJson(Map<String, Object> body) {
        try {
            return mapper.writer().writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("微信支付报文序列化失败", ex);
        }
    }

    private long parseTimestamp(String timestamp) {
        try {
            return Long.parseLong(timestamp);
        } catch (Exception ex) {
            return 0L;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    private String textOf(String json, String field) {
        try {
            JsonNode node = mapper.readTree(json).get(field);
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception ex) {
            return null;
        }
    }

    private String nowSeconds() {
        return String.valueOf(Instant.now().getEpochSecond());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return java.util.HexFormat.of().formatHex(buffer);
    }

    /** 统一下单结果。 */
    public record PrepayResult(String prepayId, PayParams payParams) {

        /** 与小程序 {@code wx.requestPayment} 参数一致。 */
        public record PayParams(String timeStamp, String nonceStr, String packageValue, String signType,
                                String paySign) {
        }
    }
}
