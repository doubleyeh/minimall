package com.minimall.infra.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 敏感配置的信封加密(AES-256-GCM)。
 *
 * <p>密文格式 {@code enc:Base64(nonce || ciphertext || tag)};带前缀是为了能把密文与"降级模式下的
 * 明文"区分开 —— 没配主密钥时 {@link #encrypt} 原样返回并在启动时告警,只在本地开发用。
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);

    private static final String PREFIX = "enc:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey masterKey;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(SecretProperties properties) {
        this.masterKey = loadKey(properties.masterKey());
    }

    /** 加密;未配主密钥时原样返回(降级)。 */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || masterKey == null) {
            return plain;
        }
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(sealed, 0, combined, nonce.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception ex) {
            throw new IllegalStateException("敏感配置加密失败", ex);
        }
    }

    /** 解密;非密文(降级模式写入的明文)原样返回。 */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty() || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (masterKey == null) {
            throw new IllegalStateException("存在密文配置,但没有配置 minimall.secret.master-key");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            byte[] nonce = new byte[NONCE_LENGTH];
            System.arraycopy(combined, 0, nonce, 0, NONCE_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] plain = cipher.doFinal(combined, NONCE_LENGTH, combined.length - NONCE_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("敏感配置解密失败(主密钥不匹配或密文被篡改)", ex);
        }
    }

    public boolean isConfigured() {
        return masterKey != null;
    }

    private SecretKey loadKey(String configured) {
        if (configured == null || configured.isBlank()) {
            log.warn("未配置 minimall.secret.master-key,敏感配置将以明文存储,仅限本地开发使用");
            return null;
        }
        byte[] keyBytes = Base64.getDecoder().decode(configured.trim());
        if (keyBytes.length != 32) {
            throw new IllegalStateException("minimall.secret.master-key 必须是 Base64 的 32 字节");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }
}
