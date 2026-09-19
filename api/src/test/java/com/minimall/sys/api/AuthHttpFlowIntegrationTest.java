package com.minimall.sys.api;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 认证链路的**真 HTTP** 端到端用例(架构文档 7.1.1、7.1.2、7.1.3、8.1)。
 *
 * <p><b>为什么必须是真 HTTP(而不是调 service)</b>:
 * <ul>
 *   <li>Sa-Token 的上下文由 servlet filter 建立({@code SaTokenContextFilterForJakartaServlet},
 *       order = -104)。在测试线程里直接调 {@code AuthService.login} 会抛
 *       {@code SaTokenContextException} —— 登录这条路径**天生只能在 HTTP 链路上验证**</li>
 *   <li>本项目真实踩过的两个坑都只在这条链路上暴露:①{@code TenantWebFilter} 的 order 排在了
 *       -104 之前,导致每个请求都 500;②登录发生在"身份确认之前",被数据权限过滤按 denyAll
 *       拦空,表现成"账号密码都对却登录失败"。服务层用例自己先绑了上下文,永远看不见这两类问题</li>
 *   <li>它同时把"客户端按 {@code Authorization: Bearer <token>} 传令牌"这个约定钉住了:
 *       少了 {@code sa-token.token-prefix} 配置,表现是"登录成功、下一个请求就 401"</li>
 * </ul>
 *
 * <p>HTTP 客户端用 JDK 自带的 {@code java.net.http}:Spring Boot 4 起测试支撑类的位置和
 * 默认 JSON 库(Jackson 3,包名 {@code tools.jackson})都变了,直接依赖 JDK 反而更稳,
 * 断言用正则即可(响应体是紧凑 JSON)。
 *
 * <p>用例依赖 V2 种子账号 platform / admin / admin123,并在前后各复位一次密码状态,
 * 保证可重复执行。IP 限流(10 次/分钟)跨用例累计,执行前先清一次计数器。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class AuthHttpFlowIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long SEED_ADMIN_ID = 1L;
    /** 种子数据里的"全量套餐":新建租户用它,默认管理员角色即拥有非平台菜单(含用户列表)。 */
    private static final long FULL_PACKAGE_ID = 1L;
    private static final String SEED_TENANT_CODE = "platform";
    private static final String SEED_USERNAME = "admin";
    private static final String SEED_PASSWORD = "admin123";
    private static final String NEW_PASSWORD = "Admin@123456";
    private static final String TENANT_ADMIN_NEW_PASSWORD = "Tenant@123456";
    /** 权限矩阵用例里用到的固定密码:省掉"首登改密"那一圈(见 createUserInTenant)。 */
    private static final String MATRIX_USER_PASSWORD = "Matrix@123456";
    private static final String USERS_PATH = "/system/users?pageNo=1&pageSize=5";
    private static final String ROLES_PATH = "/system/roles?pageNo=1&pageSize=5";

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private UserService userService;

    @BeforeEach
    void prepareSeedState() {
        // 登录 IP 限流(10 次/分钟)按 IP 计数;跨用例累计会让断言莫名其妙地拿到 429
        redis.delete(redis.keys("*127.0.0.1*"));
        restoreSeedUser();
    }

    @AfterEach
    void resetSeedState() {
        restoreSeedUser();
    }

    @Test
    @DisplayName("登录 → 强制改密闸门 → 改密踢会话 → 新令牌可用 → 刷新轮换 → 匿名/伪造 401")
    void fullAuthenticationFlowOverHttp() throws Exception {
        // —— 1. 登录:请求里没有任何身份,靠 tenantCode 现场定位租户 ——
        Response login = post("/auth/login",
                "{\"tenantCode\":\"" + SEED_TENANT_CODE + "\",\"username\":\"" + SEED_USERNAME
                        + "\",\"password\":\"" + SEED_PASSWORD + "\",\"deviceId\":\"it-http-1\"}", null);
        assertThat(login.code()).as("登录必须成功:响应=%s", login.body()).isEqualTo("0");
        String token = login.text("token");
        assertThat(token).isNotBlank();
        assertThat(login.text("refreshToken")).isNotBlank();
        assertThat(login.body()).as("种子账号首登必须被要求改密(7.1.2)").contains("\"mustChangePassword\":true");
        assertThat(login.body()).as("权限快照不能为空(5.5)").contains("\"permCodes\":[\"");

        // —— 2. 强制改密闸门:业务接口 403 + 业务码,白名单接口放行 ——
        Response blocked = get(USERS_PATH, token);
        assertThat(blocked.status()).as("闸门用 403 而不是 401,前端才能区分").isEqualTo(403);
        assertThat(blocked.code()).isNotEqualTo("0");
        assertThat(get("/auth/permissions", token).status()).as("闸门白名单").isEqualTo(200);

        // —— 3. 改密 ——
        assertThat(post("/auth/password",
                "{\"oldPassword\":\"" + SEED_PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}", token)
                .code()).isEqualTo("0");

        // —— 4. 改密后旧会话必须失效(7.1.2):否则泄漏出去的令牌仍然有效 ——
        assertThat(get(USERS_PATH, token).status()).isEqualTo(401);

        // 刷新令牌也要一起撤:只踢访问令牌的话,拿着改密前那张刷新令牌还能再换一张新的进来(8.1 用例 22)
        assertThat(post("/auth/refresh", refreshBody(login.text("refreshToken")), null).code())
                .as("改密后原刷新令牌必须立即不可用")
                .isNotEqualTo("0");

        // —— 5. 新密码登录后业务接口真的可用(证明上一步是"会话被清"而不是链路坏了) ——
        Response relogin = post("/auth/login",
                "{\"tenantCode\":\"" + SEED_TENANT_CODE + "\",\"username\":\"" + SEED_USERNAME
                        + "\",\"password\":\"" + NEW_PASSWORD + "\",\"deviceId\":\"it-http-2\"}", null);
        assertThat(relogin.code()).isEqualTo("0");
        assertThat(relogin.body()).contains("\"mustChangePassword\":false");
        String freshToken = relogin.text("token");

        Response users = get(USERS_PATH, freshToken);
        assertThat(users.status()).isEqualTo(200);
        assertThat(users.code()).isEqualTo("0");
        assertThat(users.body()).as("任何接口都不得输出密码字段").doesNotContain("\"password\":");

        // 平台级权限码同样通(超管 4.10)
        assertThat(get("/system/tenants?pageNo=1&pageSize=5", freshToken).status()).isEqualTo(200);

        // —— 6. 刷新令牌轮换:新令牌可用,旧刷新令牌一次性 ——
        Response refreshed = post("/auth/refresh",
                "{\"refreshToken\":\"" + relogin.text("refreshToken") + "\"}", null);
        assertThat(refreshed.code()).isEqualTo("0");
        String rotatedToken = refreshed.text("token");
        assertThat(rotatedToken).isNotBlank();
        assertThat(get(USERS_PATH, rotatedToken).status()).isEqualTo(200);
        assertThat(post("/auth/refresh",
                "{\"refreshToken\":\"" + relogin.text("refreshToken") + "\"}", null).code())
                .as("旧刷新令牌必须已作废(7.1.3 轮换 + 重放处置)")
                .isNotEqualTo("0");

        // —— 7. 无令牌 / 伪造令牌 ——
        assertThat(get(USERS_PATH, null).status()).isEqualTo(401);
        assertThat(get(USERS_PATH, "not-a-real-token").status()).isEqualTo(401);
    }

    @Test
    @DisplayName("用例18:租户被禁用后,在线会话在下一次请求即失效(4.11),并且无法再登录")
    void disabledTenantKillsOnlineSessionsImmediately() throws Exception {
        // 平台超管按 4.7 的六步建一个独立租户(走服务层的真实入口,不 mock)
        String tenantCode = "http-" + suffix();
        String adminUsername = "admin" + suffix();
        TenantCreateResponse created = asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                tenantCode, "禁用用例租户", FULL_PACKAGE_ID, null, adminUsername, "用例管理员", null)));

        // 1) 该租户管理员首登被强制改密 → 改密 → 再登一次拿可用令牌
        String initialPassword = created.initialPassword();
        Response firstLogin = post("/auth/login", loginBody(tenantCode, adminUsername, initialPassword), null);
        assertThat(firstLogin.code()).isEqualTo("0");
        assertThat(firstLogin.body()).contains("\"mustChangePassword\":true");
        assertThat(post("/auth/password", "{\"oldPassword\":\"" + initialPassword
                + "\",\"newPassword\":\"" + TENANT_ADMIN_NEW_PASSWORD + "\"}", firstLogin.text("token")).code())
                .isEqualTo("0");

        Response login = post("/auth/login", loginBody(tenantCode, adminUsername, TENANT_ADMIN_NEW_PASSWORD), null);
        assertThat(login.code()).isEqualTo("0");
        String token = login.text("token");
        // 先确认业务接口本来就通,否则后面的 401 可能只是"本来就调不通"
        assertThat(get(USERS_PATH, token).status()).isEqualTo(200);

        // 2) 平台禁用该租户
        asSuperUser(() -> {
            tenantService.changeStatus(created.tenantId(), 0);
            return null;
        });

        // 3) 同一个令牌的下一次请求立即被挡:不能等会话自然过期(4.11)
        assertThat(get(USERS_PATH, token).status())
                .as("租户被禁用必须立刻体现在在线会话上,否则停用的租户还能继续操作")
                .isEqualTo(401);

        // 4) 也无法再登录,且文案与"密码错误"完全一致(不暴露"这个租户被禁用了")
        Response blockedLogin = post("/auth/login",
                loginBody(tenantCode, adminUsername, TENANT_ADMIN_NEW_PASSWORD), null);
        assertThat(blockedLogin.code()).isNotEqualTo("0");
        assertThat(blockedLogin.body()).contains("用户名或密码错误");

        // 5) 刷新同样要挡:否则被停用的租户还能靠刷新令牌继续换新令牌进来(8.1 用例 21)
        assertThat(post("/auth/refresh", refreshBody(login.text("refreshToken")), null).status())
                .as("租户被禁用后,刷新接口必须 401")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("8.2:角色 × 接口的通过/403 矩阵(HTTP 层实测,不靠人工点)")
    void permissionMatrixDecidesAccessOverHttp() throws Exception {
        String tenantCode = "mx-" + suffix();
        String adminUsername = "admin" + suffix();
        TenantCreateResponse tenant = asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                tenantCode, "矩阵用例租户", FULL_PACKAGE_ID, null, adminUsername, "用例管理员", null)));
        long tenantId = tenant.tenantId();
        long adminUserId = tenant.adminUserId();

        // 两个角色各自只授一条链:用户列表(system:user:list)与角色列表(system:role:list)
        long userListRole = createRoleInTenant(tenantId, adminUserId, List.of(1L, 2L, 11L));
        long roleListRole = createRoleInTenant(tenantId, adminUserId, List.of(1L, 3L, 21L));
        String userListUser = createUserInTenant(tenantId, adminUserId, "mxuser", userListRole);
        String roleListUser = createUserInTenant(tenantId, adminUserId, "mxrole", roleListRole);

        String userListToken = loginAndGetToken(tenantCode, userListUser);
        assertThat(get(USERS_PATH, userListToken).status()).as("有「用户列表」权限 → 通过").isEqualTo(200);
        Response deniedByUserListUser = get(ROLES_PATH, userListToken);
        assertThat(deniedByUserListUser.status())
                .as("没有「角色列表」权限 → 403,响应体=%s", deniedByUserListUser.body())
                .isEqualTo(403);
        assertThat(deniedByUserListUser.code()).isNotEqualTo("0");

        String roleListToken = loginAndGetToken(tenantCode, roleListUser);
        assertThat(get(ROLES_PATH, roleListToken).status()).isEqualTo(200);
        Response deniedByRoleListUser = get(USERS_PATH, roleListToken);
        assertThat(deniedByRoleListUser.status())
                .as("没有「用户列表」权限 → 403,响应体=%s", deniedByRoleListUser.body())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("用例21:用户被禁用后,刷新令牌换不出新令牌")
    void disabledUserCannotRefreshToken() throws Exception {
        String tenantCode = "ru-" + suffix();
        String adminUsername = "admin" + suffix();
        TenantCreateResponse tenant = asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                tenantCode, "刷新用例租户", FULL_PACKAGE_ID, null, adminUsername, "用例管理员", null)));
        long roleId = createRoleInTenant(tenant.tenantId(), tenant.adminUserId(), List.of(1L, 2L, 11L));
        String username = createUserInTenant(tenant.tenantId(), tenant.adminUserId(), "refreshuser", roleId);

        Response login = post("/auth/login", loginBody(tenantCode, username, MATRIX_USER_PASSWORD), null);
        assertThat(login.code()).isEqualTo("0");

        // 直接改库里的状态,**不经过 changeStatus 的"踢会话 + 撤刷新令牌"**:这样才真正走到
        // "刷新时校验用户状态"那一支。两种做法都会 401,但只有这一种能证明那道校验存在 ——
        // 否则用例测到的只是"令牌早被撤销了"
        long userId = asUserInTenant(tenant.tenantId(), tenant.adminUserId(),
                () -> userRepository.findByTenantIdAndUsername(tenant.tenantId(), username).orElseThrow().getId());
        asSuperUser(() -> {
            SysUser user = userRepository.findById(userId).orElseThrow();
            user.setStatus(0);
            userRepository.save(user);
            return null;
        });

        Response refresh = post("/auth/refresh", refreshBody(login.text("refreshToken")), null);
        assertThat(refresh.status()).as("被禁用用户的刷新请求必须 401,响应体=%s", refresh.body()).isEqualTo(401);
    }

    private String loginAndGetToken(String tenantCode, String username) throws Exception {
        Response login = post("/auth/login", loginBody(tenantCode, username, MATRIX_USER_PASSWORD), null);
        assertThat(login.code()).as("登录失败:%s", login.body()).isEqualTo("0");
        return login.text("token");
    }

    private long createRoleInTenant(long tenantId, long adminUserId, List<Long> menuIds) {
        return asUserInTenant(tenantId, adminUserId, () -> {
            long roleId = roleService.create(new RoleCreateRequest("mx" + suffix(), "矩阵用例角色", 1, null, 1));
            roleService.grantMenus(roleId, new RoleMenuGrantRequest(menuIds));
            return roleId;
        });
    }

    private String createUserInTenant(long tenantId, long adminUserId, String namePrefix, long roleId) {
        String username = namePrefix + suffix();
        asUserInTenant(tenantId, adminUserId, () -> {
            userService.create(new UserSaveRequest(username, MATRIX_USER_PASSWORD, "矩阵用例用户",
                    null, null, List.of(roleId), 1));
            // 首登强制改密与本用例无关:直接记为"已改密",省掉两次登录(也避开 10 次/分钟的 IP 限流窗口)
            SysUser user = userRepository.findByTenantIdAndUsername(tenantId, username).orElseThrow();
            user.setMustChangePassword(0);
            userRepository.save(user);
            return null;
        });
        return username;
    }

    /** 以该租户管理员的身份执行 setup(它不是超管,走租户内正常写入路径)。 */
    private <T> T asUserInTenant(long tenantId, long userId, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantId, false, () -> {
            AuditContext.bind(new AuditContext(tenantId, userId, "127.0.0.1", "http-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private String loginBody(String tenantCode, String username, String password) {
        return "{\"tenantCode\":\"" + tenantCode + "\",\"username\":\"" + username
                + "\",\"password\":\"" + password + "\",\"deviceId\":\"it-http-tenant\"}";
    }

    private String refreshBody(String refreshToken) {
        return "{\"refreshToken\":\"" + refreshToken + "\"}";
    }

    /** 以平台超管身份执行:建租户、禁用租户这类动作只有平台侧能做(4.10)。 */
    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, SEED_ADMIN_ID, "127.0.0.1", "http-it"));
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

    private Response post(String path, String jsonBody, String token) throws Exception {
        return send(HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)), path, token);
    }

    private Response get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder().GET(), path, token);
    }

    private Response send(HttpRequest.Builder builder, String path, String token) throws Exception {
        builder.uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            // 客户端约定:Authorization: Bearer <token>(7.1)
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }

    /** 紧凑 JSON 的极简读取:只需要"取字符串字段"和"读业务码"两个能力。 */
    private record Response(int status, String body) {

        String code() {
            Matcher matcher = Pattern.compile("\"code\":(\\d+)").matcher(body);
            return matcher.find() ? matcher.group(1) : "?";
        }

        String text(String field) {
            Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        }
    }

    /**
     * 把种子账号复位成"初始密码 + 强制改密"。
     *
     * <p>用例会真的改密,不复位的话第二次执行就会因为"密码不是 admin123"而失败,
     * 变成一条只能跑一次的测试。
     */
    private void restoreSeedUser() {
        TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            SysUser user = userRepository.findById(SEED_ADMIN_ID).orElseThrow();
            user.setPassword(passwordEncoder.encode(SEED_PASSWORD));
            user.setMustChangePassword(1);
            user.setLoginFailCount(0);
            user.setLockTime(null);
            userRepository.save(user);
            return null;
        });
    }
}
