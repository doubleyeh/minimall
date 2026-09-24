package com.minimall.mall.infra.pay;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 微信支付 V3 的签名、验签与回调解密(只用 JDK,不引第三方依赖)。
 *
 * <p>三处容易被写错、且错了只会表现为"莫名失败"的细节:签名串的换行与结尾、
 * 解密时 {@code associated_data} 为空不能传 AAD、GCM 的 authTag 由 JDK 处理不能手动切。
 */
public final class WxPayCrypto {

    /** 回调时间戳与当前时间的最大偏差(秒),超出按伪造处理 */
    public static final long TIMESTAMP_TOLERANCE_SECONDS = 300;

    private static final String SIGN_ALGORITHM = "SHA256withRSA";
    private static final String AUTH_PREFIX = "WECHATPAY2-SHA256-RSA2048 ";
    private static final int TAG_BITS = 128;

    private WxPayCrypto() {
    }

    /** 请求签名串:{@code method\nurl\ntimestamp\nnonce\nbody\n};body 必须是实际发送的那份原文。 */
    public static String requestSignString(String method, String url, String timestamp, String nonce, String body) {
        return String.join("\n", method, url, timestamp, nonce, body == null ? "" : body) + "\n";
    }

    /** 回调/回执验签串:{@code timestamp\nnonce\nbody\n} —— 与请求签名串结构不同,不能混用。 */
    public static String callbackSignString(String timestamp, String nonce, String body) {
        return String.join("\n", timestamp, nonce, body == null ? "" : body) + "\n";
    }

    /** 用商户私钥对签名串做 SHA256withRSA,返回 Base64。 */
    public static String sign(String signString, String privateKeyPem) {
        try {
            Signature signature = Signature.getInstance(SIGN_ALGORITHM);
            signature.initSign(parsePrivateKey(privateKeyPem));
            signature.update(signString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception ex) {
            throw new IllegalStateException("微信支付签名失败", ex);
        }
    }

    /** 用微信平台公钥验签。 */
    public static boolean verify(String signString, String signature, String platformPublicKeyPem) {
        if (platformPublicKeyPem == null || platformPublicKeyPem.isBlank() || signature == null) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(SIGN_ALGORITHM);
            verifier.initVerify(parsePublicKey(platformPublicKeyPem));
            verifier.update(signString.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception ex) {
            return false;
        }
    }

    public static String authorizationHeader(String mchId, String nonce, String signature,
                                             String timestamp, String serialNo) {
        return AUTH_PREFIX + "mchid=\"" + mchId + "\",nonce_str=\"" + nonce
                + "\",signature=\"" + signature + "\",timestamp=\"" + timestamp
                + "\",serial_no=\"" + serialNo + "\"";
    }

    /**
     * 解密回调的 {@code resource}:{@code AEAD_AES_256_GCM}。
     *
     * <p>微信给的 ciphertext 是 {@code Base64(ciphertext || authTag)},JDK 的 GCM 会自己校验末 16 字节,
     * 直接 doFinal 即可;手动切掉 tag 反而会解密失败。
     */
    public static String decryptResource(String apiV3Key, String ciphertext, String nonce, String associatedData) {
        byte[] keyBytes = apiV3Key == null ? new byte[0] : apiV3Key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("APIv3 密钥必须是 32 字节");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null && !associatedData.isEmpty()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(ciphertext));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("微信回调解密失败", ex);
        }
    }

    /** 解析商户私钥({@code apiclient_key.pem},PKCS#8)。 */
    public static PrivateKey parsePrivateKey(String pem) {
        byte[] der = Base64.getDecoder().decode(stripPem(pem));
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("商户私钥解析失败,应为 PKCS#8 PEM", ex);
        }
    }

    /** 解析微信平台公钥:既支持平台证书(CERTIFICATE),也支持直接给公钥(PUBLIC KEY)。 */
    public static PublicKey parsePublicKey(String pem) {
        try {
            if (pem.contains("BEGIN CERTIFICATE")) {
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                        new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
                return certificate.getPublicKey();
            }
            byte[] der = Base64.getDecoder().decode(stripPem(pem));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("平台公钥解析失败", ex);
        }
    }

    /** 去掉 PEM 的首尾标记行并把内容拼成一行。 */
    public static String stripPem(String pem) {
        StringBuilder builder = new StringBuilder();
        for (String line : pem.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-----")) {
                continue;
            }
            builder.append(trimmed);
        }
        return builder.toString();
    }
}
