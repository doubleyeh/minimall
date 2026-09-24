package com.minimall.mall.service.support;

import com.minimall.infra.security.SecretCipher;
import com.minimall.mall.infra.pay.WxPayConfigProvider;
import com.minimall.mall.infra.pay.WxPayCrypto;
import com.minimall.mall.service.PayService;
import com.minimall.sys.domain.SysWxPayConfig;
import com.minimall.sys.domain.Tenant;
import com.minimall.sys.domain.repository.SysWxPayConfigRepository;
import com.minimall.sys.domain.repository.TenantRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试用的微信回调夹具:造出**真的**签名与密文,并给租户写好能验过签的配置。
 *
 * <p>为什么不能用明文 DTO 糊过去:接入真实渠道后回调就是密文,再留一条"明文也能置为已支付"的
 * 捷径,等于留下一个不用验签就能改订单状态的口子。所以这些用例走的必须是和线上一样的
 * 验签 + 解密路径。
 *
 * <p>密钥是测试内现造的(不是写死一份签名),配置也按用例里的租户现写。
 */
@Component
public class WxPayCallbackFixture {

    public static final String API_V3_KEY = "01234567890123456789012345678901";
    public static final String PLATFORM_SERIAL = "test-platform-serial";

    private static final KeyPair KEY_PAIR = keyPair();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SysWxPayConfigRepository configRepository;
    private final TenantRepository tenantRepository;
    private final SecretCipher cipher;
    private final WxPayConfigProvider configProvider;
    private final PayService payService;
    private final ObjectMapper mapper = new ObjectMapper();

    public WxPayCallbackFixture(SysWxPayConfigRepository configRepository, TenantRepository tenantRepository,
                                SecretCipher cipher, WxPayConfigProvider configProvider, PayService payService) {
        this.configRepository = configRepository;
        this.tenantRepository = tenantRepository;
        this.cipher = cipher;
        this.configProvider = configProvider;
        this.payService = payService;
    }

    /** 让该租户有一套与夹具密钥对应的配置。 */
    public void ensureConfig(String tenantCode) {
        Tenant tenant = tenantRepository.findByTenantCode(tenantCode).orElseThrow();
        Long tenantId = tenant.getId();
        SysWxPayConfig config = configRepository.findByTenantId(tenantId).orElseGet(SysWxPayConfig::new);
        config.setTenantId(tenantId);
        config.setPayMode(SysWxPayConfig.MODE_DIRECT);
        config.setMchId("1900000001");
        config.setAppId("wxappid");
        config.setAppSecretEnc(cipher.encrypt("app-secret"));
        config.setLoginAppSource(SysWxPayConfig.LOGIN_SOURCE_APP);
        config.setApiV3KeyEnc(cipher.encrypt(API_V3_KEY));
        config.setMerchantSerialNo("test-merchant-serial");
        config.setMerchantPrivateKeyEnc(cipher.encrypt(privatePem()));
        config.setPlatformSerialNo(PLATFORM_SERIAL);
        config.setPlatformPublicKeyEnc(cipher.encrypt(publicPem()));
        config.setStatus(1);
        configRepository.save(config);
        configProvider.evict(tenantId);
    }

    /** 平台租户的支付成功回调(与旧用例"把订单置为已支付"等价)。 */
    public void paySuccess(String outTradeNo, BigDecimal amount) {
        paySuccess("platform", outTradeNo, amount);
    }

    public void paySuccess(String tenantCode, String outTradeNo, BigDecimal amount) {
        ensureConfig(tenantCode);
        Payload payload = payload("TRANSACTION.SUCCESS", Map.of(
                "out_trade_no", outTradeNo,
                "transaction_id", "test-txn-" + System.nanoTime(),
                "trade_state", "SUCCESS",
                "amount", Map.of("total", toCents(amount), "currency", "CNY")), "transaction");
        payService.handlePayCallback(tenantCode, payload.timestamp(), payload.nonce(),
                payload.serial(), payload.signature(), payload.body());
    }

    /** 退款回调;{@code success} 为假时投递 {@code REFUND.CLOSED}。 */
    public void refund(String tenantCode, String outRefundNo, String wxRefundId, boolean success) {
        ensureConfig(tenantCode);
        Payload payload = payload(success ? "REFUND.SUCCESS" : "REFUND.CLOSED", Map.of(
                "out_refund_no", outRefundNo,
                "refund_id", wxRefundId,
                "refund_status", success ? "SUCCESS" : "CLOSED"), "refund");
        payService.handleRefundCallback(tenantCode, payload.timestamp(), payload.nonce(),
                payload.serial(), payload.signature(), payload.body());
    }

    /** 供 HTTP 层用例使用:直接拿到投递所需的各个字段。 */
    public Payload payload(String eventType, Map<String, Object> resource, String associatedData) {
        try {
            // 两个 nonce 不是一回事:头里的 Wechatpay-Nonce 参与签名,resource 里的 nonce 是 GCM 的
            String resourceNonce = randomText(12);
            String headerNonce = HexFormat.of().formatHex(randomBytes(16));
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String ciphertext = seal(mapper.writer().writeValueAsString(resource), resourceNonce, associatedData);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("id", "notify-" + System.nanoTime());
            envelope.put("create_time", Instant.now().toString());
            envelope.put("event_type", eventType);
            envelope.put("resource_type", "encrypt-resource");
            envelope.put("resource", Map.of(
                    "algorithm", "AEAD_AES_256_GCM",
                    "ciphertext", ciphertext,
                    "nonce", resourceNonce,
                    "associated_data", associatedData));
            String body = mapper.writer().writeValueAsString(envelope);
            String signature = WxPayCrypto.sign(
                    WxPayCrypto.callbackSignString(timestamp, headerNonce, body), privatePem());
            return new Payload(timestamp, headerNonce, PLATFORM_SERIAL, signature, body);
        } catch (Exception ex) {
            throw new IllegalStateException("构造回调失败", ex);
        }
    }

    /** 投递回调需要的全部入参。 */
    public record Payload(String timestamp, String nonce, String serial, String signature, String body) {
    }

    public static String privatePem() {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    public static String publicPem() {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(KEY_PAIR.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    public static int toCents(BigDecimal yuan) {
        return yuan.setScale(2, RoundingMode.HALF_UP).movePointRight(2).intValueExact();
    }

    private String seal(String plain, String nonce, String associatedData) throws Exception {
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
        if (associatedData != null && !associatedData.isEmpty()) {
            gcm.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(gcm.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private static String randomText(int length) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < length; i++) {
            builder.append((char) ('a' + RANDOM.nextInt(26)));
        }
        return builder.toString();
    }

    private static byte[] randomBytes(int length) {
        byte[] buffer = new byte[length];
        RANDOM.nextBytes(buffer);
        return buffer;
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("生成测试密钥对失败", ex);
        }
    }
}
