package com.minimall.infra.tenant;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.minimall.common.ErrorCode;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.security.SessionKeys;
import com.minimall.infra.web.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 租户识别与租户状态校验(架构文档 4.2、4.9、4.11)。
 *
 * <p><b>职责边界:只做"识别与校验",不做过滤器的启用</b>。原因是时序——servlet filter 阶段
 * {@code OpenEntityManagerInViewInterceptor} 还没跑,请求上没有绑定 Session,在这里 enable 过滤器
 * 会被随后创建的 Session 覆盖掉。过滤器的启用统一由事务切面在事务边界完成(见 4.2 的说明)。
 *
 * <p>它做三件事:
 * <ol>
 *   <li><b>识别租户</b>:已登录时从 Sa-Token 会话扩展数据取(Sa-Token 自己按 token 索引到会话,
 *       不存在伪造的可能);未登录的公开接口允许用请求头 {@code X-Tenant-Code} 定租户(4.9 的第二态)</li>
 *   <li><b>校验租户状态</b>(4.11):禁用/过期 → 清会话 + 401,且文案统一,不区分"不存在/已过期/已禁用"</li>
 *   <li><b>构造审计快照</b>(4.12):把 tenantId/userId/ip/traceId 装进 {@link AuditContext},
 *       供 4.4 的审计填充与异步落库使用</li>
 * </ol>
 *
 * <p>最后兜底的是"默认拒绝"那一层:识别不到租户时并不拒绝请求,而是让 TenantContext 留空 ——
 * 事务切面会把租户过滤条件绑成恒假值,租户表查询返回空集。这样漏设上下文的后果是"查不到数据"
 * (立刻暴露),而不是"看到所有人的数据"(数据泄露)。
 */
@Component
@Order(TenantWebFilter.ORDER)
public class TenantWebFilter extends OncePerRequestFilter {

    /**
     * 本过滤器在链中的位置。**这个值不是"越早越好"调出来的,而是被 sa-token 钉死的**:
     *
     * <p>sa-token 的自动配置({@code SaTokenContextRegister})以 {@code setOrder(-104)} 注册了
     * {@code SaTokenContextFilterForJakartaServlet},它负责把"当前请求的 Sa-Token 上下文"
     * 放进 ThreadLocal。而本过滤器要用 {@link StpUtil} 读登录态(tenantId / userId / 是否超管),
     * 必须**晚于**它 —— 否则 {@code StpUtil} 取不到上下文,抛
     * {@code SaTokenContextException: SaTokenContext 上下文尚未初始化}。
     *
     * <p>这个坑的迷惑性在于失败形态:**每一个 HTTP 请求都 500**(登录、匿名请求也不例外),
     * 而不是某个接口 401/403 —— 看上去像"应用整体坏了",实际只是过滤器顺序差了一位。
     * 所以这里刻意不写 {@code Ordered.HIGHEST_PRECEDENCE + n} 那种相对值:那个写法会得到
     * -2147483638,远早于 -104,是必然踩坑的"越早越好"直觉(本文件就是这么错过一次的)。
     *
     * <p>升级 sa-token 后若又出现该异常,第一件事就是重新确认 -104 是否变了
     * (可用 {@code javap -c cn.dev33.satoken.spring.SaTokenContextRegister} 看 setOrder 的常量)。
     */
    public static final int ORDER = -103;

    /** 会话扩展数据的 key:统一取自 {@link com.minimall.infra.security.SessionKeys},避免读写两端各写一份字符串。 */
    public static final String SESSION_TENANT_ID = SessionKeys.TENANT_ID;
    public static final String SESSION_SUPER_USER = SessionKeys.SUPER_USER;

    /** 未登录的公开接口用这个请求头定租户(4.9)。它只代表"这是哪个租户",不代表任何身份。 */
    public static final String TENANT_CODE_HEADER = "X-Tenant-Code";

    private static final Logger log = LoggerFactory.getLogger(TenantWebFilter.class);

    private final TenantProperties properties;
    private final TenantLookup tenantLookup;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public TenantWebFilter(TenantProperties properties, TenantLookup tenantLookup) {
        this.properties = properties;
        this.tenantLookup = tenantLookup;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String path = request.getRequestURI();
            String ip = resolveClientIp(request);
            String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
            AuditContext.bind(new AuditContext(null, null, ip, traceId));

            boolean publicPath = isPublicPath(path);
            Long tenantId = null;
            boolean superUser = false;
            boolean mustChangePassword = false;

            if (StpUtil.isLogin()) {
                SaSession session = StpUtil.getSession();
                tenantId = toLong(session.get(SESSION_TENANT_ID));
                superUser = Boolean.TRUE.equals(session.get(SESSION_SUPER_USER));
                mustChangePassword = Boolean.TRUE.equals(session.get(SessionKeys.MUST_CHANGE_PASSWORD));
                AuditContext.bind(new AuditContext(tenantId, toLong(StpUtil.getLoginIdDefaultNull()), ip, traceId));
            }

            if (tenantId == null) {
                // 4.9 的第二态:未登录的公开接口允许用请求头定租户(此时过滤器照常启用)
                tenantId = resolveByHeader(request);
            }

            // 4.11:租户状态校验(带缓存)。禁用/过期 → 清会话 + 401,文案统一
            if (tenantId != null && !tenantUsable(tenantId, superUser)) {
                if (StpUtil.isLogin()) {
                    StpUtil.logout();
                }
                log.info("租户不可用,请求被拒: tenantId={} uri={} traceId={}", tenantId, path, traceId);
                writeUnauthorized(response, ErrorCode.TENANT_ABNORMAL);
                return;
            }

            // 7.1.2:强制改密拦截。**服务端兜底,不依赖前端跳转** —— 只放行改密、登出、刷新权限三个接口
            if (mustChangePassword && !isAllowedDuringForcedPasswordChange(path)) {
                log.info("未改初始密码,请求被拒: userId={} uri={}", StpUtil.getLoginIdDefaultNull(), path);
                writeJson(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.PASSWORD_CHANGE_REQUIRED);
                return;
            }

            // 4.9 的兜底:既不在白名单、又没有租户标识,说明这个请求没有登录态。
            // 返回 401 而不是 403 —— 前端要靠 401 分流到登录页,403 会被理解成"已登录但无权限"。
            // 注意这里不依赖 Sa-Token 的注解校验:没有 @SaCheckPermission 的接口也拦得住
            if (!publicPath && tenantId == null) {
                writeUnauthorized(response, ErrorCode.UNAUTHORIZED);
                return;
            }

            TenantContext.setTenantId(tenantId);
            TenantContext.setSuperUser(superUser);

            filterChain.doFilter(request, response);
        } finally {
            // 必须清:线程池/虚拟线程复用会让下一个请求串上这次的租户身份(4.1、4.12)
            TenantContext.clear();
            AuditContext.clear();
        }
    }

    /**
     * 租户状态校验(4.11):走缓存,不每次查库。
     *
     * <p>超管豁免这一条:平台租户永不过期/不会被禁用,否则平台租户被误禁用会导致谁都进不了后台。
     */
    private boolean tenantUsable(Long tenantId, boolean superUser) {
        if (superUser) {
            return true;
        }
        return tenantLookup.byId(tenantId).filter(TenantSnapshot::usable).isPresent();
    }

    /**
     * 已登录的用户,其 token 里带的租户信息来自登录时写入的会话;未登录时允许显式带
     * {@code X-Tenant-Code}(4.9 的"已定租户、未登录"第二态)。
     */
    private Long resolveByHeader(HttpServletRequest request) {
        String tenantCode = request.getHeader(TENANT_CODE_HEADER);
        if (tenantCode == null || tenantCode.isBlank()) {
            return null;
        }
        return tenantLookup.byCode(tenantCode).map(TenantSnapshot::id).orElse(null);
    }

    /**
     * 路径是否在白名单里(4.9)。白名单显式配置,不做"路径里含 auth 就放行"这类模糊匹配。
     *
     * <p>注意:白名单只表示"允许没有 tenantId 就执行",**不代表免鉴权**——
     * 需要登录态的接口仍然会被 Sa-Token 拦住;真正免登录的只有 7.1.1/7.1.3/6.1 那几个。
     */
    @SuppressWarnings("unused")
    private boolean isPublicPath(String path) {
        List<String> patterns = properties.publicPaths();
        for (String pattern : patterns) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void writeUnauthorized(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, errorCode);
    }

    /**
     * 强制改密期间允许访问的接口(7.1.2)。
     *
     * <p>放在过滤器里而不是靠前端跳转:短信/脚本/直接调接口都能绕过前端,
     * "必须改密"是服务端约束,不是界面行为。
     */
    private boolean isAllowedDuringForcedPasswordChange(String path) {
        return path.equals("/auth/password")
                || path.equals("/auth/logout")
                || path.equals("/auth/permissions");
    }

    private void writeJson(HttpServletResponse response, int httpStatus, ErrorCode errorCode) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + errorCode.code() + ",\"message\":\"" + errorCode.message() + "\"}");
    }
}
