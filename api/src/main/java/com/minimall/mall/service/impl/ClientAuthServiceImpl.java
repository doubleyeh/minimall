package com.minimall.mall.service.impl;

import com.minimall.api.mall.dto.ClientLoginResponse;
import com.minimall.api.mall.dto.WxLoginRequest;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.infra.tenant.TenantLookup;
import com.minimall.infra.tenant.TenantSnapshot;
import com.minimall.mall.domain.MallCustomer;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import com.minimall.mall.infra.auth.ClientTokenService;
import com.minimall.mall.infra.auth.WxAuthClient;
import com.minimall.mall.service.ClientAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 小程序客户登录实现(商城设计文档 3.1)。
 *
 * <p>三点刻意的选择:
 * <ol>
 *   <li><b>整个流程都在 {@code TenantContext.callAsTenant} 里执行</b>:登录时还没有任何租户上下文,
 *       而客户查询受租户过滤器保护 —— 不进入上下文,按 openid 查客户会命中空集,
 *       表现为"每次登录都新建一个客户",而且是静默的(不报错)</li>
 *   <li><b>租户不可用时统一返回"登录失败"</b>:与后台登录同一口径(7.1.1),
 *       不区分"租户不存在/被禁用/已过期",避免被用来探测租户状态</li>
 *   <li><b>客户被禁用时也返回登录失败</b>:但**令牌签发在前、状态校验在后**是错的 ——
 *       顺序反了会让被禁用的客户拿到新令牌,而过滤器要到下一次请求才拦住它</li>
 * </ol>
 */
@Service
@Transactional
public class ClientAuthServiceImpl implements ClientAuthService {

    private static final Logger log = LoggerFactory.getLogger(ClientAuthServiceImpl.class);

    private final MallCustomerRepository customerRepository;
    private final ClientTokenService tokenService;
    private final WxAuthClient wxAuthClient;
    private final TenantLookup tenantLookup;

    public ClientAuthServiceImpl(MallCustomerRepository customerRepository,
                                 ClientTokenService tokenService,
                                 WxAuthClient wxAuthClient,
                                 TenantLookup tenantLookup) {
        this.customerRepository = customerRepository;
        this.tokenService = tokenService;
        this.wxAuthClient = wxAuthClient;
        this.tenantLookup = tenantLookup;
    }

    @Override
    public ClientLoginResponse wxLogin(String tenantCode, WxLoginRequest request) {
        TenantSnapshot tenant = resolveTenant(tenantCode);
        WxAuthClient.WxSession session = wxAuthClient.code2Session(request.code())
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED, "微信登录失败,请重试"));

        return TenantContext.callAsTenant(tenant.id(), false, () -> {
            MallCustomer customer = customerRepository.findByTenantIdAndOpenid(tenant.id(), session.openid())
                    .orElse(null);
            boolean isNew = customer == null;
            if (isNew) {
                customer = new MallCustomer();
                customer.setOpenid(session.openid());
                customer.setUnionid(session.unionid());
                customer.setStatus(1);
                customer.setPoints(0);
                customer.setGrowthValue(0);
                customer.setRegisterTime(LocalDateTime.now());
                customer = customerRepository.save(customer);
                log.info("新客户注册: tenantId={} customerId={}", tenant.id(), customer.getId());
            } else if (customer.getStatus() == null || customer.getStatus() != 1) {
                // 禁用客户不给令牌:否则他能拿着新令牌到处试,直到某个接口忘了校验状态
                throw new BusinessException(ErrorCode.LOGIN_FAILED, "账号不可用,请联系商家");
            }
            customer.setLastLoginTime(LocalDateTime.now());

            String token = tokenService.issue(tenant.id(), customer.getId());
            return new ClientLoginResponse(token, tokenService.ttlSeconds(), isNew, customer.getId(),
                    customer.getNickname(), customer.getAvatarUrl());
        });
    }

    /** 租户编码 → 可用租户。任何异常情况都用同一个错误码,不暴露租户是否存在(7.1.1)。 */
    private TenantSnapshot resolveTenant(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        return tenantLookup.byCode(tenantCode)
                .filter(TenantSnapshot::usable)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));
    }
}
