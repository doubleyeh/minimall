package com.minimall.sys.service.impl;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.infra.security.SecretCipher;
import com.minimall.mall.infra.pay.WxPayConfigProvider;
import com.minimall.sys.api.dto.WxPayConfigSaveRequest;
import com.minimall.sys.api.dto.WxPayConfigView;
import com.minimall.sys.domain.QTenant;
import com.minimall.sys.domain.SysWxPayConfig;
import com.minimall.sys.domain.Tenant;
import com.minimall.sys.domain.repository.SysWxPayConfigRepository;
import com.minimall.sys.domain.repository.TenantRepository;
import com.minimall.sys.service.WxPayConfigService;
import com.querydsl.core.BooleanBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 租户微信支付配置的管理实现。
 *
 * <p>写入后一律清掉支付侧的缓存(写后删),让下一次下单立刻读到新配置。
 */
@Service
@Transactional
public class WxPayConfigServiceImpl implements WxPayConfigService {

    private static final Set<String> MODES = Set.of(SysWxPayConfig.MODE_DIRECT, SysWxPayConfig.MODE_PARTNER);
    private static final Set<String> LOGIN_SOURCES =
            Set.of(SysWxPayConfig.LOGIN_SOURCE_APP, SysWxPayConfig.LOGIN_SOURCE_SUB);

    private final SysWxPayConfigRepository configRepository;
    private final TenantRepository tenantRepository;
    private final SecretCipher cipher;
    private final WxPayConfigProvider configProvider;

    public WxPayConfigServiceImpl(SysWxPayConfigRepository configRepository, TenantRepository tenantRepository,
                                  SecretCipher cipher, WxPayConfigProvider configProvider) {
        this.configRepository = configRepository;
        this.tenantRepository = tenantRepository;
        this.cipher = cipher;
        this.configProvider = configProvider;
    }

    @Override
    public PageResult<WxPayConfigView> page(String tenantCode, Integer status, int pageNo, int pageSize) {
        QTenant qTenant = QTenant.tenant;
        BooleanBuilder where = new BooleanBuilder();
        if (tenantCode != null && !tenantCode.isBlank()) {
            where.and(qTenant.tenantCode.contains(tenantCode));
        }
        if (status != null) {
            where.and(qTenant.status.eq(status));
        }
        Page<Tenant> page = tenantRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1)));

        Map<Long, SysWxPayConfig> configs = configsOf(page.getContent().stream().map(Tenant::getId).toList());
        List<WxPayConfigView> views = page.getContent().stream()
                .map(tenant -> toView(tenant, configs.get(tenant.getId())))
                .toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public WxPayConfigView get(Long tenantId) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "租户不存在"));
        return toView(tenant, configRepository.findByTenantId(tenantId).orElse(null));
    }

    @Override
    @Transactional
    public Long create(WxPayConfigSaveRequest request) {
        if (request.tenantId() == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少租户");
        }
        tenantRepository.findById(request.tenantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "租户不存在"));
        if (configRepository.findByTenantId(request.tenantId()).isPresent()) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "该租户已配置微信支付");
        }
        validate(request, true);

        SysWxPayConfig config = new SysWxPayConfig();
        config.setTenantId(request.tenantId());
        apply(config, request, true);
        configRepository.save(config);
        configProvider.evict(request.tenantId());
        return config.getId();
    }

    @Override
    @Transactional
    public void update(Long tenantId, WxPayConfigSaveRequest request) {
        SysWxPayConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该租户尚未配置微信支付"));
        validate(request, false);
        apply(config, request, false);
        configRepository.save(config);
        configProvider.evict(tenantId);
    }

    @Override
    @Transactional
    public void updateStatus(Long tenantId, Integer status) {
        SysWxPayConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "该租户尚未配置微信支付"));
        config.setStatus(status);
        configRepository.save(config);
        configProvider.evict(tenantId);
    }

    /** 校验两种模式各自的必填项;创建时密钥类字段也必填。 */
    private void validate(WxPayConfigSaveRequest request, boolean creating) {
        if (!MODES.contains(request.payMode())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "支付模式只能是 direct 或 partner");
        }
        String loginSource = loginSource(request);
        if (!LOGIN_SOURCES.contains(loginSource)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "登录凭据来源只能是 app 或 sub");
        }
        if (SysWxPayConfig.MODE_PARTNER.equals(request.payMode()) && isBlank(request.subMchId())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "服务商模式必须填特约商户号");
        }
        if (SysWxPayConfig.LOGIN_SOURCE_SUB.equals(loginSource) && isBlank(request.subAppId())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "登录用 sub 凭据时必须填特约商户小程序 appId");
        }
        if (!isBlank(request.apiV3Key()) && request.apiV3Key().trim().length() != 32) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "APIv3 密钥必须是 32 位");
        }
        if (creating) {
            requireOnCreate(request.appSecret(), "appSecret");
            requireOnCreate(request.apiV3Key(), "APIv3 密钥");
            requireOnCreate(request.merchantPrivateKey(), "商户私钥");
            if (SysWxPayConfig.LOGIN_SOURCE_SUB.equals(loginSource)) {
                requireOnCreate(request.subAppSecret(), "特约商户小程序 appSecret");
            }
        }
    }

    private void apply(SysWxPayConfig config, WxPayConfigSaveRequest request, boolean creating) {
        config.setPayMode(request.payMode());
        config.setMchId(request.mchId());
        config.setSubMchId(request.subMchId());
        config.setAppId(request.appId());
        config.setSubAppId(request.subAppId());
        config.setLoginAppSource(loginSource(request));
        config.setMerchantSerialNo(request.merchantSerialNo());
        config.setPlatformSerialNo(request.platformSerialNo());
        config.setRemark(request.remark());
        config.setStatus(request.status() == null ? 1 : request.status());
        keepOrReplaceSecret(request.appSecret(), creating, config.getAppSecretEnc(), config::setAppSecretEnc);
        keepOrReplaceSecret(request.subAppSecret(), creating, config.getSubAppSecretEnc(), config::setSubAppSecretEnc);
        keepOrReplaceSecret(request.apiV3Key(), creating, config.getApiV3KeyEnc(), config::setApiV3KeyEnc);
        keepOrReplaceSecret(request.merchantPrivateKey(), creating,
                config.getMerchantPrivateKeyEnc(), config::setMerchantPrivateKeyEnc);
        keepOrReplaceSecret(request.platformPublicKey(), creating,
                config.getPlatformPublicKeyEnc(), config::setPlatformPublicKeyEnc);
    }

    /** 请求里留空表示"不改这一项";创建时留空就是空值。 */
    private void keepOrReplaceSecret(String incoming, boolean creating, String current, Consumer<String> setter) {
        if (isBlank(incoming)) {
            setter.accept(creating ? null : current);
            return;
        }
        setter.accept(cipher.encrypt(incoming.trim()));
    }

    private void requireOnCreate(String value, String label) {
        if (isBlank(value)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "缺少" + label);
        }
    }

    private String loginSource(WxPayConfigSaveRequest request) {
        return isBlank(request.loginAppSource()) ? SysWxPayConfig.LOGIN_SOURCE_APP : request.loginAppSource();
    }

    private Map<Long, SysWxPayConfig> configsOf(Collection<Long> tenantIds) {
        if (tenantIds.isEmpty()) {
            return Map.of();
        }
        return configRepository.findByTenantIdIn(tenantIds).stream()
                .collect(Collectors.toMap(SysWxPayConfig::getTenantId, Function.identity(), (a, b) -> a));
    }

    private WxPayConfigView toView(Tenant tenant, SysWxPayConfig config) {
        if (config == null) {
            return new WxPayConfigView(tenant.getId(), tenant.getTenantCode(), tenant.getTenantName(),
                    false, null, null, null, null, null, null, null, null,
                    false, false, false, false, false, null, null, null);
        }
        return new WxPayConfigView(tenant.getId(), tenant.getTenantCode(), tenant.getTenantName(),
                true, config.getPayMode(), config.getMchId(), config.getSubMchId(),
                config.getAppId(), config.getSubAppId(), config.getLoginAppSource(),
                config.getMerchantSerialNo(), config.getPlatformSerialNo(),
                hasText(config.getAppSecretEnc()), hasText(config.getSubAppSecretEnc()),
                hasText(config.getApiV3KeyEnc()), hasText(config.getMerchantPrivateKeyEnc()),
                hasText(config.getPlatformPublicKeyEnc()),
                config.getStatus(), config.getRemark(), config.getUpdateTime());
    }

    private boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
