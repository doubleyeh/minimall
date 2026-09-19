package com.minimall.api.mall;

import com.minimall.api.mall.dto.ClientLoginResponse;
import com.minimall.api.mall.dto.WxLoginRequest;
import com.minimall.common.ApiResponse;
import com.minimall.mall.service.ClientAuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序端 - 客户登录(商城设计文档 3.1)。
 *
 * <p>路径在 4.9 的公开路径白名单里({@code /mall/api/auth/wx-login}),
 * 所以不需要令牌;但**必须带 {@code X-Tenant-Code}** 才能定位租户 —— 微信 openid 是按小程序发放的,
 * 没有租户就无从查起。
 *
 * <p>刻意不加 {@code @SaCheckPermission}:这是客户端接口,后台的权限体系与它无关。
 */
@RestController
@RequestMapping("/mall/api/auth")
public class ClientAuthController {

    private final ClientAuthService clientAuthService;

    public ClientAuthController(ClientAuthService clientAuthService) {
        this.clientAuthService = clientAuthService;
    }

    @PostMapping("/wx-login")
    public ApiResponse<ClientLoginResponse> wxLogin(
            @RequestHeader(name = "X-Tenant-Code", required = false) String tenantCode,
            @Valid @RequestBody WxLoginRequest request) {
        return ApiResponse.ok(clientAuthService.wxLogin(tenantCode, request));
    }
}
