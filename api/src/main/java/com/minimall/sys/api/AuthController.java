package com.minimall.sys.api;

import com.minimall.sys.api.dto.ChangePasswordRequest;
import com.minimall.sys.api.dto.LoginRequest;
import com.minimall.sys.api.dto.LoginResponse;
import com.minimall.sys.api.dto.PermissionSnapshot;
import com.minimall.sys.api.dto.RefreshTokenRequest;
import com.minimall.sys.api.dto.RefreshTokenResponse;
import com.minimall.common.ApiResponse;
import com.minimall.infra.audit.AuditLog;
import com.minimall.sys.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口(架构文档 7.1.1、7.1.2)。
 *
 * <p>职责边界:只做"接参数 + 调 service + 包响应",不写任何业务逻辑,也不注入 Repository。
 *
 * <p>本控制器是唯一"发生在 TenantWebFilter 定租户之后、但没有登录态"的一组接口
 * (登录本身在 {@code minimall.tenant.public-paths} 白名单里,由过滤器按请求体之外的规则放行)。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录。租户是否存在/禁用/过期、用户是否存在、密码是否正确,失败一律返回同一文案(见 ErrorCode.LOGIN_FAILED)。
     *
     * <p>**要记操作日志**:登录失败(尤其是连续失败触发锁定)是最有价值的排查线索。
     * 请求体里的明文密码由切面按黑名单掩码后再落库(见 7.2),不会明文进日志表。
     * 登录成功时切面会在方法返回后重新取一次审计快照,所以这条日志能记到真实用户(见 OperLogAspect)。
     */
    @PostMapping("/login")
    @AuditLog(module = "认证", permCode = "auth:login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    /**
     * 刷新访问令牌(架构文档 7.1.3)。
     *
     * <p>**白名单接口**:访问令牌过期后才会调它,因此不校验登录态,也不需要 `Authorization` 头。
     * 租户上下文由服务端从刷新令牌载荷恢复(见 {@code AuthService#refresh} 的实现顺序)。
     */
    @PostMapping("/refresh")
    public ApiResponse<RefreshTokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }

    /**
     * 登出:清理 Sa-Token 会话,并撤销本次登录的刷新令牌(7.1.3)。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.ok();
    }

    /**
     * 修改密码:成功后该用户全部会话失效(架构文档 7.1.2),前端需要引导重新登录。
     *
     * <p>改密必须留痕(安全审计的基本要求),请求体里的新旧密码同样由切面掩码。
     */
    @PostMapping("/password")
    @AuditLog(module = "认证", permCode = "auth:password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ApiResponse.ok();
    }

    /**
     * 刷新当前用户的权限快照(架构文档 5.5)。前端收到 403 时调用一次。
     */
    @GetMapping("/permissions")
    public ApiResponse<PermissionSnapshot> permissions() {
        return ApiResponse.ok(authService.currentPermissions());
    }
}
