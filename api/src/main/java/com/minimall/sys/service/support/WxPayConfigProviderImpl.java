package com.minimall.sys.service.support;

import com.minimall.infra.security.SecretCipher;
import com.minimall.mall.infra.pay.WxPayConfigProvider;
import com.minimall.mall.infra.pay.WxPayCredentials;
import com.minimall.mall.infra.pay.WxPayProperties;
import com.minimall.sys.domain.SysWxPayConfig;
import com.minimall.sys.domain.repository.SysWxPayConfigRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 租户微信支付凭据的读取与缓存。
 *
 * <p>缓存里存的是库里的**密文**而不是明文,所以"缓存被读到"与"库被读到"的损失等价,
 * 不会新增一个明文泄露面;解密只在内存里发生。
 *
 * <p>未配置用哨兵值做负缓存:下单接口会被反复调用,不能让"某租户没配支付"每次都打库。
 */
@Service
public class WxPayConfigProviderImpl implements WxPayConfigProvider {

    private static final String KEY_PREFIX = "mall:pay:wx:cfg:";
    private static final String MISS = "-";
    private static final String DELIMITER = "|";

    private final SysWxPayConfigRepository repository;
    private final StringRedisTemplate redis;
    private final WxPayProperties properties;
    private final SecretCipher cipher;

    public WxPayConfigProviderImpl(SysWxPayConfigRepository repository, StringRedisTemplate redis,
                                   WxPayProperties properties, SecretCipher cipher) {
        this.repository = repository;
        this.redis = redis;
        this.properties = properties;
        this.cipher = cipher;
    }

    @Override
    public Optional<WxPayCredentials> byTenantId(Long tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        String cached = redis.opsForValue().get(KEY_PREFIX + tenantId);
        if (cached != null) {
            return MISS.equals(cached) ? Optional.empty() : Optional.of(decode(tenantId, cached));
        }
        Optional<SysWxPayConfig> found = repository.findByTenantId(tenantId);
        if (found.isEmpty() || !found.get().isEnabled()) {
            redis.opsForValue().set(KEY_PREFIX + tenantId, MISS,
                    Duration.ofSeconds(properties.configCacheMissSecondsOrDefault()));
            return Optional.empty();
        }
        String encoded = encode(found.get());
        redis.opsForValue().set(KEY_PREFIX + tenantId, encoded,
                Duration.ofSeconds(properties.configCacheSecondsOrDefault()));
        return Optional.of(decode(tenantId, encoded));
    }

    @Override
    public void evict(Long tenantId) {
        if (tenantId != null) {
            redis.delete(KEY_PREFIX + tenantId);
        }
    }

    private String encode(SysWxPayConfig config) {
        return String.join(DELIMITER,
                nullToEmpty(config.getPayMode()),
                nullToEmpty(config.getMchId()),
                nullToEmpty(config.getSubMchId()),
                nullToEmpty(config.getAppId()),
                nullToEmpty(config.getAppSecretEnc()),
                nullToEmpty(config.getSubAppId()),
                nullToEmpty(config.getSubAppSecretEnc()),
                nullToEmpty(config.getLoginAppSource()),
                nullToEmpty(config.getApiV3KeyEnc()),
                nullToEmpty(config.getMerchantSerialNo()),
                nullToEmpty(config.getMerchantPrivateKeyEnc()),
                nullToEmpty(config.getPlatformSerialNo()),
                nullToEmpty(config.getPlatformPublicKeyEnc()));
    }

    private WxPayCredentials decode(Long tenantId, String value) {
        String[] parts = value.split("\\" + DELIMITER, -1);
        return new WxPayCredentials(
                tenantId,
                part(parts, 0),
                part(parts, 1),
                part(parts, 2),
                part(parts, 3),
                cipher.decrypt(part(parts, 4)),
                part(parts, 5),
                cipher.decrypt(part(parts, 6)),
                part(parts, 7),
                cipher.decrypt(part(parts, 8)),
                part(parts, 9),
                cipher.decrypt(part(parts, 10)),
                part(parts, 11),
                cipher.decrypt(part(parts, 12)));
    }

    private String part(String[] parts, int index) {
        if (index >= parts.length || parts[index].isEmpty()) {
            return null;
        }
        return parts[index];
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
