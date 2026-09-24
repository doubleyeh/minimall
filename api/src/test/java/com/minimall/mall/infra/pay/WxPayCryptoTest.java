package com.minimall.mall.infra.pay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信支付签名、验签与回调解密的验证。
 *
 * <p>这几处错了不会有编译错误,只会在联调时表现为"微信说签名不对",所以必须在单测里钉死:
 * 签名串的换行结构、时间戳窗口、以及 GCM 的 authTag 由 JDK 处理。
 *
 * <p>密钥是测试内现造的(不是固定密钥对),避免测试变成"验证一份写死的签名"。
 */
class WxPayCryptoTest {

    private static final String API_V3_KEY = "01234567890123456789012345678901";
    private static final String NONCE = "abcdefghijkl";

    @Test
    @DisplayName("请求签名串是 method\\nurl\\ntimestamp\\nnonce\\nbody\\n")
    void requestSignStringLayout() {
        String signString = WxPayCrypto.requestSignString("POST", "/v3/pay/transactions/jsapi", "1", "n", "b");
        assertThat(signString).isEqualTo("POST\n/v3/pay/transactions/jsapi\n1\nn\nb\n");
    }

    @Test
    @DisplayName("回调验签串是 timestamp\\nnonce\\nbody\\n,与请求签名串不同")
    void callbackSignStringLayout() {
        assertThat(WxPayCrypto.callbackSignString("1", "n", "b")).isEqualTo("1\nn\nb\n");
    }

    @Test
    @DisplayName("签名能被对应公钥验过;改一个字节就验不过")
    void signAndVerify() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        String privatePem = privatePem(keyPair);
        String publicPem = publicPem(keyPair);

        String signString = WxPayCrypto.callbackSignString("1700000000", NONCE, "{\"a\":1}");
        String signature = WxPayCrypto.sign(signString, privatePem);

        assertThat(signature).isNotBlank();
        assertThat(WxPayCrypto.verify(signString, signature, publicPem)).isTrue();
        assertThat(WxPayCrypto.verify(signString + "x", signature, publicPem)).as("改了内容就验不过").isFalse();
        assertThat(WxPayCrypto.verify(WxPayCrypto.callbackSignString("1700000001", NONCE, "{\"a\":1}"),
                signature, publicPem)).as("不同时间戳的串不能复用签名").isFalse();
    }

    @Test
    @DisplayName("密钥或签名格式不对时返回 false,而不是抛异常")
    void verifyNeverThrows() {
        String signString = WxPayCrypto.callbackSignString("1", NONCE, "body");
        assertThat(WxPayCrypto.verify(signString, null, "-----BEGIN PUBLIC KEY-----")).isFalse();
        assertThat(WxPayCrypto.verify(signString, "not-base64-!!", null)).isFalse();
        assertThat(WxPayCrypto.verify(signString, null, null)).isFalse();
    }

    @Test
    @DisplayName("Authorization 头按微信要求的顺序与引号拼装")
    void authorizationHeaderFormat() {
        String header = WxPayCrypto.authorizationHeader("1900000001", "nonce1", "sig", "1700000000", "SER1");
        assertThat(header).isEqualTo("WECHATPAY2-SHA256-RSA2048 mchid=\"1900000001\",nonce_str=\"nonce1\""
                + ",signature=\"sig\",timestamp=\"1700000000\",serial_no=\"SER1\"");
    }

    @Test
    @DisplayName("回调 resource 解密:AES-GCM 往返,associated_data 可空")
    void decryptResourceRoundTrip() throws Exception {
        String plain = "{\"out_trade_no\":\"O1\"}";
        assertThat(WxPayCrypto.decryptResource(API_V3_KEY, seal(plain, ""), NONCE, ""))
                .as("associated_data 为空也要能解").isEqualTo(plain);
        assertThat(WxPayCrypto.decryptResource(API_V3_KEY, seal(plain, "txn"), NONCE, "txn"))
                .as("带 associated_data 也要能解").isEqualTo(plain);
    }

    @Test
    @DisplayName("密钥不对或密文被改,解密应当失败")
    void decryptFailsOnBadInput() throws Exception {
        String cipher = seal("{\"a\":1}", "");
        assertThatThrownBy(() -> WxPayCrypto.decryptResource(API_V3_KEY.replace('0', '9'), cipher, NONCE, ""))
                .isInstanceOf(IllegalStateException.class);

        byte[] tampered = Base64.getDecoder().decode(cipher);
        tampered[0] ^= 0x01;
        String tamperedCipher = Base64.getEncoder().encodeToString(tampered);
        assertThatThrownBy(() -> WxPayCrypto.decryptResource(API_V3_KEY, tamperedCipher, NONCE, ""))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> WxPayCrypto.decryptResource("short", cipher, NONCE, ""))
                .as("APIv3 密钥不是 32 字节要直接失败").isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("私钥与公钥的 PEM 都能解析出来")
    void parsePem() throws Exception {
        KeyPair keyPair = rsaKeyPair();
        assertThat(WxPayCrypto.parsePrivateKey(privatePem(keyPair))).isNotNull();
        assertThat(WxPayCrypto.parsePublicKey(publicPem(keyPair))).isNotNull();
    }

    /** 按微信的格式加密:{@code Base64(ciphertext || authTag)}。 */
    private String seal(String plain, String associatedData) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(API_V3_KEY.getBytes(StandardCharsets.UTF_8), "AES"),
                new GCMParameterSpec(128, NONCE.getBytes(StandardCharsets.UTF_8)));
        if (!associatedData.isEmpty()) {
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
    }

    private KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String privatePem(KeyPair keyPair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    private String publicPem(KeyPair keyPair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }
}
