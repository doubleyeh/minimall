package com.minimall.mall.infra.auth;

import com.minimall.common.ErrorCode;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.infra.tenant.TenantFilterService;
import com.minimall.infra.tenant.TenantWebFilter;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import jakarta.persistence.EntityManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 客户端(小程序)身份识别(商城设计文档 3.1)。
 *
 * <p><b>这是与后台完全独立的一条过滤链</b>,只作用于 {@code /mall/api/**}:
 * <ul>
 *   <li>令牌:自研 JWT({@link ClientTokenService}),不走 Sa-Token 会话</li>
 *   <li>身份:{@link ClientContext} 里的 {@code mall_customer.id},不是 {@code sys_user.id}</li>
 *   <li>租户:来自令牌里的 {@code tenantId},并**覆盖** {@link TenantContext} ——
 *       租户以签名令牌为准,而不是请求头(请求头是客户端可伪造的)</li>
 * </ul>
 *
 * <p>顺序必须**晚于** {@link TenantWebFilter}:后者在 {@code TenantContext} 里放过
 * {@code X-Tenant-Code} 解析出的租户(供未登录的公开接口使用),这里再用令牌里的租户覆盖它。
 * 覆盖之后要重新调用 {@code tenantFilterService.apply} —— 租户值变了,过滤器绑定的参数也得跟着变,
 * 否则当前请求会按"上一个租户"过滤,表现为"刚登录却查不到自己的购物车"。
 *
 * <p><b>每次请求都校验客户状态</b>:JWT 在过期前无法撤销,客户被禁用时只能靠这里拦住。
 * 这次查询按主键走(有租户过滤保护),开销可接受;若将来客户端 QPS 上量,
 * 可以把"客户是否可用"放进 Redis 缓存(与 4.11 的租户状态缓存同理)。
 */
@Component
@Order(ClientAuthFilter.ORDER)
public class ClientAuthFilter extends OncePerRequestFilter {

    /** 晚于 {@link TenantWebFilter}(-103),保证"先识别租户、再按令牌覆盖"。 */
    public static final int ORDER = TenantWebFilter.ORDER + 1;

    /** 只接管小程序端接口;后台 {@code /system/**}、{@code /mall/admin/**} 一律不碰。 */
    private static final String CLIENT_PATH_PREFIX = "/mall/api/";

    /**
     * 不需要客户端令牌的路径。**默认是"要令牌",这里只放行明确的例外** ——
     * 反过来写(默认放行、列出要鉴权的)迟早会漏掉一个新增接口。
     *
     * <p>包含两类:
     * <ul>
     *   <li>登录本身({@code wx-login}):此时还没有令牌</li>
     *   <li>商品浏览(分类、列表、详情):小程序里"先逛后登录"是常态,
     *       强制登录会明显增加流失。它们仍受租户隔离保护(租户来自 {@code X-Tenant-Code})</li>
     * </ul>
     */
    private static final String[] PUBLIC_PATTERNS = {
            "/mall/api/auth/wx-login",
            "/mall/api/categories",
            "/mall/api/goods",
            "/mall/api/goods/**",
            // 领券列表:与商品浏览同理,先看到优惠再登录,转化更好。
            // 注意只放行"可领取列表",/mine(我的券)与 /claim(领取)仍需要令牌
            "/mall/api/coupons/claimable"
    };

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final Logger log = LoggerFactory.getLogger(ClientAuthFilter.class);

    private final ClientTokenService tokenService;
    private final TenantFilterService tenantFilterService;
    private final MallCustomerRepository customerRepository;
    private final EntityManager entityManager;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ClientAuthFilter(ClientTokenService tokenService,
                            TenantFilterService tenantFilterService,
                            MallCustomerRepository customerRepository,
                            EntityManager entityManager) {
        this.tokenService = tokenService;
        this.tenantFilterService = tenantFilterService;
        this.customerRepository = customerRepository;
        this.entityManager = entityManager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith(CLIENT_PATH_PREFIX) || isPublicPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            Optional<ClientPrincipal> principal = tokenService.parse(resolveToken(request));
            if (principal.isEmpty()) {
                writeUnauthorized(response);
                return;
            }
            ClientPrincipal client = principal.get();

            // 先设上下文再查客户 —— 客户的查询本身也受租户过滤保护,顺序反了会查到空
            TenantContext.setTenantId(client.tenantId());
            ClientContext.set(client);
            tenantFilterService.apply(entityManager);

            var customer = customerRepository.findById(client.customerId()).orElse(null);
            if (customer == null || customer.getStatus() == null || customer.getStatus() != 1) {
                // 令牌有效但客户不可用:禁用必须立即生效,不能等令牌过期(30 天)
                log.info("客户不可用,请求被拒: customerId={} tenantId={} path={}",
                        client.customerId(), client.tenantId(), path);
                writeUnauthorized(response);
                return;
            }

            filterChain.doFilter(request, response);
        } finally {
            ClientContext.clear();
        }
    }

    private boolean isPublicPath(String path) {
        for (String pattern : PUBLIC_PATTERNS) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    /** 客户端统一按 {@code Authorization: Bearer <token>} 传入(与后台一致,前端只维护一套请求层)。 */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null) {
            return null;
        }
        if (header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return header.trim();
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + ErrorCode.UNAUTHORIZED.code()
                + ",\"message\":\"" + ErrorCode.UNAUTHORIZED.message() + "\"}");
    }
}
