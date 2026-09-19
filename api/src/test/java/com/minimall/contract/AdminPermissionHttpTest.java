package com.minimall.contract;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import com.minimall.sys.api.dto.RoleCreateRequest;
import com.minimall.sys.api.dto.RoleMenuGrantRequest;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import com.minimall.sys.api.dto.UserSaveRequest;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.repository.SysUserRepository;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.service.RoleService;
import com.minimall.sys.service.TenantService;
import com.minimall.sys.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端端点的权限矩阵(架构文档 5.2、8.2)。
 *
 * <p><b>为什么需要这个用例</b>:权限码写错的代价与业务 bug 完全不同 ——
 * 业务逻辑错了会有断言失败,而 {@code @SaCheckPermission} 漏写或写错时接口**照样返回 200**,
 * 只是"任何登录用户都能调"。没有任何业务测试能发现它,人工 review 也会漏(几十个端点)。
 * 所以这里把约束固化成两条:
 * <ol>
 *   <li><b>穷举检查</b>:从 Spring 的路由表里枚举全部 {@code /system/**} 与 {@code /mall/admin/**}
 *       端点,每个都必须带非空权限码。新增端点忘了写注解,这条用例直接失败</li>
 *   <li><b>真实拒绝</b>:用一个"能登录但没有任何权限码"的账号,对**每个**管理端端点发一次真实请求,
 *       断言都是 403。这一条同时验证了"注解写了"和"拦截器真的生效"——
 *       项目里踩过 SaInterceptor 未注册、注解形同注释的坑</li>
 * </ol>
 *
 * <p>为什么枚举路由表而不是手写端点清单:手写清单会随着新增端点而腐化(漏写的那条正好不会被测),
 * 而路由表是运行时的真实状态。代价是路径变量要替换成占位值,这是可接受的。
 *
 * <p>断言顺序刻意是"先证明账号本身可用、再证明它被拒绝":否则一次登录失败会让所有端点都返回 403,
 * 用例全绿而实际什么都没验证。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class AdminPermissionHttpTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long SEED_ADMIN_ID = 1L;
    /** 种子数据里的"全量套餐":新建租户用它,这样租户侧存在商城菜单可授权。 */
    private static final long FULL_PACKAGE_ID = 1L;
    private static final String UNPRIVILEGED_PASSWORD = "Unpriv@123456";
    /** 租户管理员首登会被强制改密(7.1.2),这里用它改密后的密码再登一次。 */
    private static final String TENANT_ADMIN_NEW_PASSWORD = "TenantAdmin@123456";
    /** 平台超管用例账号的密码。该账号是直接改库造出来的,不存在首登改密。 */
    private static final String PLATFORM_ADMIN_PASSWORD = "PlatAdmin@123456";
    /** 种子数据里的平台租户编码。 */
    private static final String PLATFORM_TENANT_CODE = "platform";
    /**
     * 授给"无权限角色"的菜单:种子数据里的系统管理目录与其下的用户管理页面。
     * 两者都是 {@code menu_type != 3},**不带任何权限码** —— 这正是我们要的
     * "能登录、能看到菜单,但一个操作权限都没有"的账号。
     */
    private static final List<Long> MENUS_WITHOUT_PERMISSION = List.of(1L, 2L);
    /** 路径变量统一替换成这个值:用一个不可能存在的 ID,万一年久失修真的执行到了也不会碰到真实数据。 */
    private static final String PLACEHOLDER_ID = "999999";

    /**
     * 刻意不设权限码的管理端端点(**每个都必须写明理由**)。
     *
     * <p>这份白名单是上面那条"穷举检查"的例外出口。它必须保持极小:
     * 一旦有人图省事把端点塞进来,规则就慢慢失效了 —— 所以理由要写成"为什么加权限码反而是错的",
     * 而不是"暂时先不加"。
     */
    private static final Map<String, String> PERMISSION_FREE_ENDPOINTS = Map.of(
            "/system/dicts/values/{dictType}",
            "字典只读接口:只返回 label/value 给前端渲染下拉与翻译取值。它加了权限码的话,"
                    + "租户必须被授予平台配置菜单才能显示下拉框 —— 而后台并不需要下发平台配置就该能用字典。");

    private final HttpClient http = HttpClient.newHttpClient();

    /** 管理端端点三要素:路径、HTTP 方法、声明的权限码(可为空,那正是要被检出的情况)。 */
    private record AdminEndpoint(String pattern, String httpMethod, String permCode, String handler) {
    }

    /** 精简响应:只关心状态码与业务码。 */
    private record Response(int status, String body) {

        String code() {
            Matcher matcher = Pattern.compile("\"code\":(\\d+)").matcher(body);
            return matcher.find() ? matcher.group(1) : "-1";
        }

        String text(String field) {
            Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        }
    }

    @LocalServerPort
    private int port;

    /**
     * 必须按 bean 名字限定:Actuator 会额外注册一个 {@code controllerEndpointHandlerMapping},
     * 它们同类型,按类型注入会因为"找到两个候选"直接报错(而且注入错了会枚举到 Actuator 自己的端点)。
     */
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private UserService userService;
    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private RedisConnectionFactory redisConnectionFactory;
    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    private String tenantCode;
    private String unprivilegedUsername;
    private String tenantAdminUsername;
    private String tenantAdminInitialPassword;
    private String platformAdminUsername;

    @BeforeEach
    void setUpTenantAndUnprivilegedUser() {
        pointSaTokenDaoToCurrentContext();
        // 登录按 IP 限流(10 次/分钟):与本类其它用例累计会让断言莫名其妙拿到 429
        redis.delete(redis.keys("*127.0.0.1*"));
        String suffix = suffix();
        tenantCode = "perm-http-" + suffix;
        tenantAdminUsername = "admin" + suffix;
        TenantCreateResponse tenant = asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                tenantCode, "权限矩阵HTTP用例租户" + suffix, FULL_PACKAGE_ID, null,
                tenantAdminUsername, "用例管理员", null)));
        tenantAdminInitialPassword = tenant.initialPassword();

        // 一个只挂了"无权限码菜单"的角色 + 一个挂该角色的用户
        unprivilegedUsername = "unpriv" + suffix;
        asUserInTenant(tenant.tenantId(), tenant.adminUserId(), () -> {
            Long roleId = roleService.create(new RoleCreateRequest("noperm" + suffix, "无权限角色",
                    // dataScope 取最窄的"仅本人":让它即使被误放行也读不到别人的数据
                    1, null, 1));
            roleService.grantMenus(roleId, new RoleMenuGrantRequest(MENUS_WITHOUT_PERMISSION));
            userService.create(new UserSaveRequest(unprivilegedUsername, UNPRIVILEGED_PASSWORD,
                    "无权限用户", null, null, List.of(roleId), 1));
            // 首登强制改密与本用例无关:直接记为"已改密",省掉两次登录(也避开 10 次/分钟的 IP 限流)
            SysUser user = userRepository.findByTenantIdAndUsername(tenant.tenantId(), unprivilegedUsername)
                    .orElseThrow();
            user.setMustChangePassword(0);
            userRepository.save(user);
            return null;
        });

        // 平台超管账号。is_super 按 4.10 只能由种子数据/运维脚本设置,接口既不接受也不暴露它,
        // 所以这里直接改库(与上面给无权限账号置 mustChangePassword=0 是同一个手法)。
        // 它换来的是别的用例覆盖不到的一条通路:平台级端点(租户/套餐/菜单/字典)的正向执行。
        platformAdminUsername = "platadmin" + suffix;
        asSuperUser(() -> {
            userService.create(new UserSaveRequest(platformAdminUsername, PLATFORM_ADMIN_PASSWORD,
                    "平台用例超管", null, null, List.of(), 1));
            SysUser superUser = userRepository
                    .findByTenantIdAndUsername(PLATFORM_TENANT_ID, platformAdminUsername)
                    .orElseThrow();
            superUser.setIsSuper(1);
            superUser.setMustChangePassword(0);
            userRepository.save(superUser);
            return null;
        });
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("穷举:每个管理端端点都必须声明权限码(漏写时接口照样 200,只是谁都能调)")
    void everyAdminEndpointDeclaresPermission() {
        List<AdminEndpoint> endpoints = adminEndpoints();
        // 端点数远小于真实值说明路由前缀变了或枚举失败 —— 那样下面的检查会"全绿但没覆盖"
        assertThat(endpoints).as("管理端端点数量异常,请确认路由前缀").hasSizeGreaterThan(50);

        List<String> missing = endpoints.stream()
                .filter(endpoint -> endpoint.permCode() == null || endpoint.permCode().isBlank())
                .filter(endpoint -> !PERMISSION_FREE_ENDPOINTS.containsKey(endpoint.pattern()))
                .map(endpoint -> "%s %s (%s)".formatted(endpoint.httpMethod(), endpoint.pattern(), endpoint.handler()))
                .distinct()
                .toList();

        assertThat(missing)
                .as("""
                        以下管理端端点没有 @SaCheckPermission。漏写不会报错,而是任何登录用户都能调 ——
                        业务测试永远发现不了这类问题。
                        如果某个端点确实不该要权限码,请加入 PERMISSION_FREE_ENDPOINTS 并把理由写成
                        "为什么加权限码反而是错的"。""")
                .isEmpty();
    }

    @Test
    @DisplayName("真实请求:无权限账号访问每个管理端端点都被拒(403)")
    void unprivilegedUserIsDeniedOnEveryAdminEndpoint() throws Exception {
        String token = loginUnprivileged();

        // 先证明这个令牌本身可用,否则下面"全部 403"可能只是登录根本没成功
        assertThat(get("/auth/permissions", token).status())
                .as("令牌必须有效,否则下面的 403 说明不了任何问题")
                .isEqualTo(200);

        List<String> notDenied = new ArrayList<>();
        for (AdminEndpoint endpoint : adminEndpoints()) {
            if (PERMISSION_FREE_ENDPOINTS.containsKey(endpoint.pattern())) {
                continue;
            }
            Response response = call(endpoint, token);
            if (response.status() != 403) {
                notDenied.add("%s %s -> HTTP %s (业务码 %s,期望 403)"
                        .formatted(endpoint.httpMethod(), endpoint.pattern(), response.status(), response.code()));
            }
        }

        assertThat(notDenied)
                .as("""
                        以下端点没有拦住"已登录但无任何权限"的账号。两种可能:
                        ①注解没写或权限码写错;②SaInterceptor 没有覆盖该路径(注解形同注释)。
                        注意 401 不属于通过:那说明租户/登录态在过滤链上就断了,同样要修。""")
                .isEmpty();
    }

    @Test
    @DisplayName("白名单端点(字典只读)无权限也能访问 —— 防止被顺手加上权限码")
    void permissionFreeEndpointsStayReachable() throws Exception {
        String token = loginUnprivileged();
        Response response = get("/system/dicts/values/order_pay_timeout_minutes", token);
        assertThat(response.status())
                .as("字典只读接口应当只要求登录:加权限码会让租户必须被授予平台配置菜单才能显示下拉框")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("反面:有权限账号不被误拦 —— 权限码写错会从\"谁都能调\"变成\"谁都用不了\"")
    void tenantAdminIsNotBlockedOnMallAdminEndpoints() throws Exception {
        String token = loginTenantAdmin();

        // 商城菜单在 V4 里授给了全量套餐,所以租户管理员天然持有 mall:* 权限码
        assertThat(get("/mall/admin/goods?pageNo=1&pageSize=5", token).status())
                .as("租户管理员应当能读商品列表:拒绝它说明权限码或套餐授权写错了")
                .isEqualTo(200);

        List<String> blocked = new ArrayList<>();
        for (AdminEndpoint endpoint : adminEndpoints()) {
            if (!endpoint.pattern().startsWith("/mall/admin/")) {
                // 平台级端点(租户/套餐/菜单/字典)对租户管理员本就应当拒绝,不在这条用例的范围内
                continue;
            }
            Response response = call(endpoint, token);
            if (response.status() == 401 || response.status() == 403) {
                blocked.add("%s %s -> HTTP %s (业务码 %s)"
                        .formatted(endpoint.httpMethod(), endpoint.pattern(), response.status(), response.code()));
            }
        }

        assertThat(blocked)
                .as("""
                        租户管理员持有 mall:* 权限码,却被这些端点拒绝。最可能的原因是权限码拼写与菜单表不一致
                        (例如端点写 mall:goods:list 而菜单里是 mall:goods:lists)—— 这种错在前端表现为
                        "按钮不见了、接口一直 403",而日志里只看到一片 403,很难定位。""")
                .isEmpty();
    }

    @Test
    @DisplayName("租户管理员:租户内的系统管理端点不被误拦(users/roles/depts)")
    void tenantAdminCanReachTenantScopedSystemEndpoints() throws Exception {
        String token = loginTenantAdmin();
        assertThat(get("/system/users?pageNo=1&pageSize=5", token).status()).isEqualTo(200);

        List<String> blocked = new ArrayList<>();
        for (AdminEndpoint endpoint : adminEndpoints()) {
            if (!isTenantScopedSystemPath(endpoint.pattern())) {
                // 平台级端点(租户/套餐/菜单/字典)对租户管理员本就应当拒绝,不在这条用例范围内
                continue;
            }
            Response response = call(endpoint, token);
            if (response.status() == 401 || response.status() == 403) {
                blocked.add("%s %s -> HTTP %s (业务码 %s)"
                        .formatted(endpoint.httpMethod(), endpoint.pattern(), response.status(), response.code()));
            }
        }

        assertThat(blocked)
                .as("""
                        租户管理员对租户内的用户/角色/部门端点被拒绝。默认管理员角色由套餐同步授权,
                        这些端点被拦通常意味着套餐授权或菜单表出了问题 —— 表现是租户建好后连自己的用户都管不了。""")
                .isEmpty();
    }

    /** 租户内可见的系统管理路径:用户、角色、部门(其余 /system/** 是平台级,租户管理员本就无权)。 */
    private boolean isTenantScopedSystemPath(String pattern) {
        return pattern.startsWith("/system/users")
                || pattern.startsWith("/system/roles")
                || pattern.startsWith("/system/depts");
    }

    @Test
    @DisplayName("平台超管:全部管理端端点都不被误拦 —— 上一条的正面对照")
    void platformSuperAdminIsNotBlockedOnAnyAdminEndpoint() throws Exception {
        String token = loginPlatformAdmin();

        // 先证明这确实是个超管:平台级接口能通(普通租户管理员在这里会被 403)。
        // 少了这一步,"全部不返回 403"也可能只是"这个账号其实什么都能过"或者根本没登录上。
        assertThat(get("/system/tenants?pageNo=1&pageSize=5", token).status())
                .as("超管必须能访问平台级接口,否则下面的断言没有意义")
                .isEqualTo(200);

        List<String> blocked = new ArrayList<>();
        for (AdminEndpoint endpoint : adminEndpoints()) {
            if (PERMISSION_FREE_ENDPOINTS.containsKey(endpoint.pattern())) {
                continue;
            }
            Response response = call(endpoint, token);
            if (response.status() == 401 || response.status() == 403) {
                blocked.add("%s %s -> HTTP %s (业务码 %s)"
                        .formatted(endpoint.httpMethod(), endpoint.pattern(), response.status(), response.code()));
            }
        }

        assertThat(blocked)
                .as("""
                        超管的权限码被 4.10 短路为"全部启用菜单",任何管理端端点都不该拒绝它。被拒只有两种可能:
                        ①该端点声明的权限码在 sys_menu 里根本不存在(短路取的就是菜单的 perm_code 集合);
                        ②该端点被过滤链或强制改密闸门拦在鉴权之前。
                        这两种都是"只有超管能调出来"的问题 —— 普通租户账号调不到这一层。""")
                .isEmpty();
    }

    /** 平台超管登录。它是直接改库造出来的账号,不需要走首登改密那一圈。 */
    private String loginPlatformAdmin() throws Exception {
        Response login = post("/auth/login",
                "{\"tenantCode\":\"" + PLATFORM_TENANT_CODE + "\",\"username\":\"" + platformAdminUsername
                        + "\",\"password\":\"" + PLATFORM_ADMIN_PASSWORD
                        + "\",\"deviceId\":\"admin-perm-platform\"}", null);
        assertThat(login.code()).as("平台超管登录应当成功:响应=%s", login.body()).isEqualTo("0");
        String token = login.text("token");
        assertThat(token).isNotBlank();
        return token;
    }

    // ------------------------------------------------------------------ 端点枚举

    /**
     * 从路由表枚举管理端端点。
     *
     * <p>权限码取"方法级优先、类级兜底":两者都支持,便于把同一模块的端点收敛到类上。
     */
    private List<AdminEndpoint> adminEndpoints() {
        List<AdminEndpoint> endpoints = new ArrayList<>();
        handlerMapping.getHandlerMethods().forEach((info, handlerMethod) -> {
            if (info.getPathPatternsCondition() == null) {
                return;
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (methods.isEmpty()) {
                return;
            }
            SaCheckPermission annotation = permissionOf(handlerMethod);
            String permCode = annotation == null || annotation.value().length == 0
                    ? null : String.join(",", annotation.value());
            String handler = handlerMethod.getBeanType().getSimpleName()
                    + "#" + handlerMethod.getMethod().getName();
            String httpMethod = methods.iterator().next().name();
            for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
                if (!pattern.startsWith("/system/") && !pattern.startsWith("/mall/admin/")) {
                    continue;
                }
                endpoints.add(new AdminEndpoint(pattern, httpMethod, permCode, handler));
            }
        });
        endpoints.sort(Comparator.comparing(AdminEndpoint::pattern).thenComparing(AdminEndpoint::httpMethod));
        return endpoints;
    }

    private SaCheckPermission permissionOf(HandlerMethod handlerMethod) {
        SaCheckPermission onMethod = AnnotatedElementUtils
                .findMergedAnnotation(handlerMethod.getMethod(), SaCheckPermission.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), SaCheckPermission.class);
    }

    // ------------------------------------------------------------------ HTTP 辅助

    private Response call(AdminEndpoint endpoint, String token) throws Exception {
        String path = endpoint.pattern().replaceAll("\\{[^}]+}", PLACEHOLDER_ID);
        HttpRequest.BodyPublisher body = "GET".equals(endpoint.httpMethod())
                || "DELETE".equals(endpoint.httpMethod())
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .method(endpoint.httpMethod(), body)
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private Response get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private Response post(String path, String jsonBody, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    /**
     * 租户管理员登录。
     *
     * <p>要两圈:首登会被强制改密闸门拦住(40301),改密后旧会话立即失效(7.1.2),必须重新登一次。
     * 这个流程本身就值得走真实 HTTP —— 直接在测试里把 mustChangePassword 置 0 会绕过它,
     * 而我们正是要确认"有权限的账号确实能拿到可用令牌",否则下面那条反面用例的 200 说明不了问题。
     */
    private String loginTenantAdmin() throws Exception {
        Response first = postLogin(tenantAdminUsername, tenantAdminInitialPassword);
        assertThat(first.code()).as("租户管理员首登应当成功:响应=%s", first.body()).isEqualTo("0");
        Response changed = post("/auth/password", "{\"oldPassword\":\"" + tenantAdminInitialPassword
                + "\",\"newPassword\":\"" + TENANT_ADMIN_NEW_PASSWORD + "\"}", first.text("token"));
        assertThat(changed.code()).as("首登必须被要求改密(7.1.2),且改密应当成功").isEqualTo("0");

        Response login = postLogin(tenantAdminUsername, TENANT_ADMIN_NEW_PASSWORD);
        assertThat(login.code()).as("改密后应当能用新密码登录:响应=%s", login.body()).isEqualTo("0");
        return login.text("token");
    }

    private Response postLogin(String username, String password) throws Exception {
        return post("/auth/login", "{\"tenantCode\":\"" + tenantCode + "\",\"username\":\"" + username
                + "\",\"password\":\"" + password + "\",\"deviceId\":\"admin-perm-it\"}", null);
    }

    private String loginUnprivileged() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"tenantCode\":\"" + tenantCode
                        + "\",\"username\":\"" + unprivilegedUsername
                        + "\",\"password\":\"" + UNPRIVILEGED_PASSWORD
                        + "\",\"deviceId\":\"admin-perm-it\"}", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        Response login = new Response(response.statusCode(), response.body());
        assertThat(login.code()).as("无权限账号也必须能登录成功:响应=%s", login.body()).isEqualTo("0");
        assertThat(login.body()).as("这个账号不能带任何权限码,否则下面的拒绝断言失去意义")
                .contains("\"permCodes\":[]");
        String token = login.text("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /**
     * 把 sa-token 的全局 DAO 重新指向当前上下文的 Redis 连接。
     *
     * <p>{@code SaManager} 是 JVM 全局静态的,而测试 JVM 里同时存在多个 Spring 上下文,
     * 每个上下文启动都会把全局 DAO 设成自己的;那个上下文被关闭后它的 Lettuce 连接就停了,
     * 后续任何走全局 DAO 的调用都会抛 {@code LettuceConnectionFactory has been STOPPED}(见 9.4)。
     */
    private void pointSaTokenDaoToCurrentContext() {
        SaTokenDaoForRedisTemplate dao = new SaTokenDaoForRedisTemplate();
        dao.init(redisConnectionFactory);
        SaManager.setSaTokenDao(dao);
    }

    private <T> T asUserInTenant(long tenantId, long userId, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantId, false, () -> {
            AuditContext.bind(new AuditContext(tenantId, userId, "127.0.0.1", "admin-perm-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, SEED_ADMIN_ID, "127.0.0.1", "admin-perm-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
