package com.minimall.service.sys.impl;

import cn.dev33.satoken.SaManager;
import com.minimall.infra.tenant.DataScopeBypass;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.minimall.api.sys.dto.ChangePasswordRequest;
import com.minimall.api.sys.dto.LoginRequest;
import com.minimall.api.sys.dto.LoginResponse;
import com.minimall.api.sys.dto.PermissionSnapshot;
import com.minimall.api.sys.dto.RefreshTokenRequest;
import com.minimall.api.sys.dto.RefreshTokenResponse;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.domain.sys.SysUser;
import com.minimall.domain.sys.repository.SysUserRepository;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.security.LoginProperties;
import com.minimall.infra.security.LoginRateLimiter;
import com.minimall.infra.security.PermissionProvider;
import com.minimall.infra.security.RefreshTokenPayload;
import com.minimall.infra.security.RefreshTokenService;
import com.minimall.infra.security.SessionKeys;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.infra.tenant.TenantFilterService;
import com.minimall.infra.tenant.TenantLookup;
import com.minimall.infra.tenant.TenantSnapshot;
import com.minimall.service.sys.AuthService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 认证实现(架构文档 7.1.1 登录 6 步、7.1.3 刷新、7.1.2 改密)。
 *
 * <p><b>关于事务与异常</b>:本类用 {@code noRollbackFor = BusinessException}。
 * 原因是登录失败要**保留**失败计数与锁定时间、刷新检测到重放要**保留**撤销结果 ——
 * 如果业务异常触发回滚,这些"做了半截但必须留住"的写入会被一起回滚掉,
 * 表现为"失败 5 次也不锁号"、"重放了但没踢下线"。这类问题不会报错,只会让安全机制静默失效。
 */
@Service
@Transactional(noRollbackFor = BusinessException.class)
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private final TenantLookup tenantLookup;
    private final SysUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final PermissionProvider permissionProvider;
    private final LoginRateLimiter loginRateLimiter;
    private final LoginProperties loginProperties;
    private final TenantFilterService tenantFilterService;

    @PersistenceContext
    private EntityManager entityManager;

    public AuthServiceImpl(TenantLookup tenantLookup,
                           SysUserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           RefreshTokenService refreshTokenService,
                           PermissionProvider permissionProvider,
                           LoginRateLimiter loginRateLimiter,
                           LoginProperties loginProperties,
                           TenantFilterService tenantFilterService) {
        this.tenantLookup = tenantLookup;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.permissionProvider = permissionProvider;
        this.loginRateLimiter = loginRateLimiter;
        this.loginProperties = loginProperties;
        this.tenantFilterService = tenantFilterService;
    }

    @Override
    public LoginResponse login(LoginRequest request) {
        AuditContext audit = AuditContext.current();
        // 第一层:IP 维度限流。超限直接 429,连租户都不查(7.1.1)
        loginRateLimiter.checkAndCount(audit.ip());

        // 第 1 步:定位租户。不存在/禁用/过期一律返回同一句"用户名或密码错误",
        // 不区分原因 —— 否则等于提供了一个批量探测有效 tenantCode 的接口
        TenantSnapshot tenant = tenantLookup.byCode(request.tenantCode())
                .filter(TenantSnapshot::usable)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

        // 登录是**唯一**"租户在事务中途才确定"的入口:必须把租户写进上下文并重新应用过滤器,
        // 否则下一步按 (tenantId, username) 查用户时,过滤器还绑着"未定租户"的哨兵值,查不到任何数据
        // 第 2 步起整段都在"身份前置"范围内执行:DataScopeBypass 让数据权限过滤整个不启用。
        // 此刻线程里还没有身份,DataScopeProvider 只能按"算不出来就拒绝"返回 denyAll,
        // 那句按 (tenant_id, username) 读 sys_user 的查询会被拦成空集 ——
        // 表现是"账号密码都对,却提示用户名或密码错误",且只在真实 HTTP 链路上出现。
        //
        // **必须包住 enterTenant**:过滤器由 apply() 启用,而 enterTenant 内部会重新 apply 一次;
        // 标志要赶在那次 apply 之前就位,否则它只对"下一轮 apply"生效,本轮查询照样被拦空。
        // 范围刻意覆盖到第 6 步:登录过程中读的都是"这个刚确认身份的用户自己的"数据
        // (用户行、角色、权限快照),它们同样不该受数据范围限制。
        return DataScopeBypass.run(() -> {
            enterTenant(tenant);

            // 第 2 步:按 (tenant_id, username) 定位用户。同名用户在不同租户下允许重复(uk_tenant_username),
            // 所以只凭 username 是查不到唯一用户的 —— 这也是登录必须传 tenantCode 的原因
            SysUser user = userRepository.findByTenantIdAndUsername(tenant.id(), request.username())
                    .orElseThrow(() -> new BusinessException(ErrorCode.LOGIN_FAILED));

            // 第 3 步:锁定期内直接拒绝,且**不再比对密码**
            // (锁定期内按密码对错返回不同提示,等于变相泄露"账号是否存在/密码是否猜对")
            if (isLocked(user)) {
                throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
            }
            if (!user.isEnabled()) {
                throw new BusinessException(ErrorCode.LOGIN_FAILED);
            }

            // 第 4 步:BCrypt 比对
            if (!passwordEncoder.matches(request.password(), user.getPassword())) {
                registerFailure(user);
                throw new BusinessException(ErrorCode.LOGIN_FAILED);
            }
            registerSuccess(user);

            // 到这里身份已确认,把审计快照补全:后面改 user 行的 update_by 才会是本人
            AuditContext.bind(new AuditContext(tenant.id(), user.getId(), audit.ip(), audit.traceId()));

            // 第 5、6 步:建会话 + 强制改密标记
            return issueTokens(user, tenant.id(), normalizeDeviceId(request.deviceId()));
        });
    }

    @Override
    public RefreshTokenResponse refresh(RefreshTokenRequest request) {
        RefreshTokenService.RotationResult result = refreshTokenService.rotate(request.refreshToken());

        if (result instanceof RefreshTokenService.RotationResult.Replayed replayed) {
            // 重放处置(7.1.3):撤销该用户全部刷新令牌 + 踢掉全部会话。
            // "宁可误伤"是有意的:代价是用户重新登录一次,收益是不放过可能已泄露的令牌
            RefreshTokenPayload payload = replayed.payload();
            refreshTokenService.revokeAll(payload.userId());
            StpUtil.logout(payload.userId());
            log.warn("检测到刷新令牌重放,已撤销全部凭据 userId={} tenantId={}", payload.userId(), payload.tenantId());
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!(result instanceof RefreshTokenService.RotationResult.Rotated rotated)) {
            // 过期、不存在、被撤销:统一 401,不区分原因(避免成为探测接口);也不要自动重试的语义
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        RefreshTokenPayload payload = rotated.payload();
        // 本接口在白名单里、没有登录态,租户只能来自令牌载荷;
        // 用载荷里的真实身份进入上下文(**不是**用超管模式,4.9/7.1.3)
        //
        // 它同样属于"身份前置":此刻线程里没有登录态(userId 为空),数据权限会算成 denyAll,
        // 下面那句按 id 读 sys_user 会被拦成空集 → 表现是"刷新令牌明明有效,却换不到新令牌"。
        return TenantContext.callAsTenant(payload.tenantId(), payload.superUser(), () -> DataScopeBypass.run(() -> {
            tenantFilterService.apply(entityManager);

            // 第 3 步:租户与用户都必须是"当下可用"的,否则换出来的新令牌等于绕过禁用(4.11)
            TenantSnapshot tenant = tenantLookup.byId(payload.tenantId()).orElse(null);
            if (tenant == null || !tenant.usable()) {
                refreshTokenService.revokeAll(payload.userId());
                log.info("租户不可用,拒绝刷新 userId={} tenantId={}", payload.userId(), payload.tenantId());
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }
            SysUser user = userRepository.findById(payload.userId()).orElse(null);
            if (user == null || !user.isEnabled()) {
                refreshTokenService.revokeAll(payload.userId());
                log.info("用户不可用,拒绝刷新 userId={}", payload.userId());
                throw new BusinessException(ErrorCode.UNAUTHORIZED);
            }

            String newAccessToken = issueAccessSession(user, tenant.id());
            return new RefreshTokenResponse(newAccessToken, rotated.newToken(), accessTokenTtlSeconds());
        }));
    }

    @Override
    public void logout() {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId != null) {
            // 只撤销"本次登录"的那张刷新令牌(它存在会话里),不影响该用户其他端的登录(7.1.3)
            Object refreshToken = StpUtil.getSession().get(SessionKeys.REFRESH_TOKEN);
            if (refreshToken != null) {
                refreshTokenService.revoke(String.valueOf(refreshToken));
            }
        }
        StpUtil.logout();
    }

    @Override
    public void changePassword(ChangePasswordRequest request) {
        Long userId = currentUserId();
        SysUser user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "原密码不正确");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "新密码不能与原密码相同");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setPwdUpdateTime(LocalDateTime.now());
        user.setMustChangePassword(0);
        user.setLoginFailCount(0);
        user.setLockTime(null);

        // 密码变了,所有凭据都该失效:撤销全部刷新令牌 + 踢掉全部会话(7.1.2、7.1.3)
        refreshTokenService.revokeAll(userId);
        StpUtil.logout(userId);
    }

    @Override
    public PermissionSnapshot currentPermissions() {
        PermissionProvider.PermissionData data = permissionProvider.load(currentUserId());
        return new PermissionSnapshot(List.copyOf(data.menus()), List.copyOf(data.permCodes()));
    }

    private Long currentUserId() {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return Long.valueOf(String.valueOf(loginId));
    }

    /** 7.1.1 第 5 步:建会话 + 写会话扩展数据 + 签发刷新令牌。 */
    private LoginResponse issueTokens(SysUser user, Long tenantId, String deviceId) {
        String accessToken = issueAccessSession(user, tenantId);
        RefreshTokenService.IssuedRefreshToken refreshToken =
                refreshTokenService.issue(user.getId(), tenantId, user.isSuperUser());
        // 刷新令牌也放进会话:登出时据此精确撤销本次登录的那一张
        StpUtil.getSession().set(SessionKeys.REFRESH_TOKEN, refreshToken.token());

        PermissionProvider.PermissionData permissions = permissionProvider.load(user.getId());
        return new LoginResponse(
                accessToken,
                refreshToken.token(),
                deviceId,
                user.getId(),
                tenantId,
                user.isSuperUser(),
                user.isMustChangePassword(),
                user.getNickname(),
                List.copyOf(permissions.menus()),
                List.copyOf(permissions.permCodes()));
    }

    private String issueAccessSession(SysUser user, Long tenantId) {
        StpUtil.login(user.getId());
        SaSession session = StpUtil.getSession();
        session.set(SessionKeys.TENANT_ID, tenantId);
        session.set(SessionKeys.SUPER_USER, user.isSuperUser());
        session.set(SessionKeys.MUST_CHANGE_PASSWORD, user.isMustChangePassword());
        return StpUtil.getTokenValue();
    }

    /**
     * 登录时把租户写进上下文并重新应用过滤器。
     *
     * <p>顺序不能颠倒:先 set 上下文、再 apply,否则过滤器绑的还是哨兵值。
     */
    private void enterTenant(TenantSnapshot tenant) {
        TenantContext.setTenantId(tenant.id());
        tenantFilterService.apply(entityManager);
    }

    /** 失效累加与锁定(7.1.1 第 4 步):达到阈值锁 15 分钟,并把计数归零重新计。 */
    private void registerFailure(SysUser user) {
        int failures = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
        if (failures >= loginProperties.maxFailCount()) {
            user.setLoginFailCount(0);
            user.setLockTime(LocalDateTime.now().plusMinutes(loginProperties.lockMinutes()));
            log.warn("账号连续失败达阈值,已锁定 userId={} 至 {}", user.getId(), user.getLockTime());
            return;
        }
        user.setLoginFailCount(failures);
    }

    private void registerSuccess(SysUser user) {
        user.setLoginFailCount(0);
        user.setLockTime(null);
    }

    private boolean isLocked(SysUser user) {
        return user.getLockTime() != null && user.getLockTime().isAfter(LocalDateTime.now());
    }

    private String normalizeDeviceId(String deviceId) {
        return (deviceId == null || deviceId.isBlank()) ? UUID.randomUUID().toString() : deviceId;
    }

    /** 访问令牌剩余秒数,取自 Sa-Token 配置(避免再定义一个可能与之不一致的配置项)。 */
    private long accessTokenTtlSeconds() {
        return SaManager.getConfig().getTimeout();
    }
}
