package com.minimall.sys.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.security.SecretCipher;
import com.minimall.mall.infra.pay.WxPayConfigProvider;
import com.minimall.mall.infra.pay.WxPayCredentials;
import com.minimall.sys.api.dto.WxPayConfigSaveRequest;
import com.minimall.sys.api.dto.WxPayConfigView;
import com.minimall.sys.domain.repository.SysWxPayConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信支付配置的读写与缓存验证。
 *
 * <p>重点在三件写了不一定立刻暴露的事:①查询视图绝不回显密钥;②库里存的是密文;
 * ③改配置后必须清掉支付侧的缓存 —— 漏了的话下单会继续用旧凭据,表现为"改了没生效"。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class WxPayConfigServiceIntegrationTest {

    private static final Long TENANT_ID = 1L;
    private static final String CACHE_KEY = "mall:pay:wx:cfg:1";
    private static final String API_V3_KEY = "01234567890123456789012345678901";
    private static final String APP_SECRET = "app-secret-plain";
    private static final String PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----";

    @Autowired
    private WxPayConfigService service;
    @Autowired
    private SysWxPayConfigRepository repository;
    @Autowired
    private WxPayConfigProvider configProvider;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private SecretCipher cipher;

    @BeforeEach
    void reset() {
        repository.findByTenantId(TENANT_ID).ifPresent(repository::delete);
        redis.delete(CACHE_KEY);
    }

    @Test
    @DisplayName("新建后查询:视图只回是否已配置,不回显任何密钥")
    void createThenViewMasksSecrets() {
        service.create(saveRequest());

        WxPayConfigView view = service.get(TENANT_ID);
        assertThat(view.configured()).isTrue();
        assertThat(view.mchId()).isEqualTo("1900000001");
        assertThat(view.appId()).isEqualTo("wxappid");
        assertThat(view.appSecretConfigured()).isTrue();
        assertThat(view.apiV3KeyConfigured()).isTrue();
        assertThat(view.merchantPrivateKeyConfigured()).isTrue();
        assertThat(view.toString()).doesNotContain(APP_SECRET, PRIVATE_KEY, API_V3_KEY);
    }

    @Test
    @DisplayName("密钥在库里是密文(配了主密钥时)")
    void secretsStoredEncrypted() {
        service.create(saveRequest());

        String stored = repository.findByTenantId(TENANT_ID).orElseThrow().getAppSecretEnc();
        if (cipher.isConfigured()) {
            assertThat(stored).startsWith("enc:").doesNotContain(APP_SECRET);
            assertThat(cipher.decrypt(stored)).isEqualTo(APP_SECRET);
        } else {
            // 没配主密钥时是降级明文,这条断言只在配了密钥的 CI 里生效
            assertThat(stored).isEqualTo(APP_SECRET);
        }
    }

    @Test
    @DisplayName("读取会写缓存;改配置后缓存被清掉")
    void cacheFilledAndEvictedOnWrite() {
        service.create(saveRequest());
        redis.delete(CACHE_KEY);

        WxPayCredentials credentials = configProvider.byTenantId(TENANT_ID).orElseThrow();
        assertThat(credentials.apiV3Key()).isEqualTo(API_V3_KEY);
        assertThat(redis.hasKey(CACHE_KEY)).isTrue();

        service.updateStatus(TENANT_ID, 0);
        assertThat(redis.hasKey(CACHE_KEY)).isFalse();
    }

    @Test
    @DisplayName("更新时密钥留空表示保持原值")
    void updateKeepsSecretsWhenBlank() {
        service.create(saveRequest());

        service.update(TENANT_ID, new WxPayConfigSaveRequest(
                null, "partner", "1900000002", "1234567890", "wxappid",
                null, null, null, "app",
                API_V3_KEY, "SER123", null, null, null, 1, null));

        WxPayConfigView view = service.get(TENANT_ID);
        assertThat(view.payMode()).isEqualTo("partner");
        assertThat(view.subMchId()).isEqualTo("1234567890");
        assertThat(view.appSecretConfigured()).as("留空不该清掉原密钥").isTrue();
        assertThat(view.merchantPrivateKeyConfigured()).as("留空不该清掉原私钥").isTrue();
    }

    @Test
    @DisplayName("服务商模式缺特约商户号、以及重复建配置,都要被拒")
    void rejectIllegalConfig() {
        assertThatThrownBy(() -> service.create(new WxPayConfigSaveRequest(
                null, "partner", "1900000002", null, "wxappid",
                APP_SECRET, null, null, "app",
                API_V3_KEY, "SER123", PRIVATE_KEY, null, null, 1, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);
        assertThat(repository.findByTenantId(TENANT_ID)).isEmpty();
    }

    @Test
    @DisplayName("同一租户只能建一次")
    void rejectDuplicate() {
        service.create(saveRequest());
        assertThatThrownBy(() -> service.create(saveRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.DATA_CONFLICT);
    }

    private WxPayConfigSaveRequest saveRequest() {
        return new WxPayConfigSaveRequest(
                TENANT_ID, "direct", "1900000001", null, "wxappid",
                APP_SECRET, null, null, "app",
                API_V3_KEY, "SER123", PRIVATE_KEY, "PLAT123",
                "-----BEGIN PUBLIC KEY-----\nfake\n-----END PUBLIC KEY-----", 1, null);
    }
}
