package com.minimall.sys.api;

import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.repository.SysUserRepository;
import com.minimall.sys.service.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码策略的端到端效果(架构文档 7.1.2):历史不可复用、有效期到期强制改密。
 *
 * <p>用属性把历史窗口设成 2、有效期设成 30 天,用例才好造场景。**默认配置里有效期是关的**
 * (开启会让所有存量用户下次登录都去改密,那是产品决定),所以这条用例必须自己带上属性,
 * 顺便也证明了"配置一开就真的生效"。
 *
 * <p>用自己建的租户账号,不动种子超管 —— 改密码会撤销全部会话,动种子账号会把别的用例一起打挂。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "minimall.login.password-expire-days=30",
                "minimall.login.password-history-count=2"
        })
@ActiveProfiles("test")
class PasswordPolicyHttpIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    private static final long FULL_PACKAGE_ID = 1L;

    @LocalServerPort
    private int port;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private StringRedisTemplate redis;

    private final HttpClient http = HttpClient.newHttpClient();
    private String tenantCode;
    private String username;
    private String password;
    private long adminUserId;

    @BeforeEach
    void setUpTenant() {
        redis.delete(redis.keys("*127.0.0.1*"));
        String code = "pwd" + suffix();
        TenantCreateResponse tenant = asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                code, "密码策略用例租户" + suffix(), FULL_PACKAGE_ID, null,
                "pwdadmin" + suffix(), "用例管理员", null)));
        tenantCode = code;
        username = tenant.adminUsername();
        password = tenant.initialPassword();
        adminUserId = tenant.adminUserId();
        asSuperUser(() -> {
            SysUser user = userRepository.findById(adminUserId).orElseThrow();
            user.setMustChangePassword(0);
            userRepository.save(user);
            return null;
        });
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("历史不可复用:改回最近用过的密码被拒;窗口之外的可以再用")
    void rejectsRecentPasswords() throws Exception {
        String p1 = password;
        String p2 = "Policy@222222";
        String p3 = "Policy@333333";
        String p4 = "Policy@444444";

        changePassword(p1, p2);
        changePassword(p2, p3);

        // 窗口是 2:此时历史里是 p2、p1 —— 改回 p1 必须被拒
        Response rejected = post("/auth/password",
                passwordBody(p3, p1), login(p3));
        assertThat(codeOf(rejected.body())).as("最近用过的密码不能再用:%s", rejected.body()).isNotZero();
        assertThat(rejected.body()).contains("最近 2 次用过的密码");

        changePassword(p3, p4);
        // 再改一次后窗口里只剩 p3、p2,p1 已经滑出窗口 —— 这时允许再用
        Response allowed = post("/auth/password", passwordBody(p4, p1), login(p4));
        assertThat(codeOf(allowed.body())).as("窗口之外的密码不该被拒:%s", allowed.body()).isZero();
    }

    @Test
    @DisplayName("有效期到期:登录后被打到改密页,且其他接口在改密前一律拒绝")
    void expiredPasswordForcesChange() throws Exception {
        expirePassword();

        Response login = post("/auth/login", loginBody(password), null);

        assertThat(codeOf(login.body())).isZero();
        assertThat(login.body()).as("到期与首登未改密走同一条路:响应里 mustChangePassword=true")
                .contains("\"mustChangePassword\":true");

        // 光有标记不够 —— 服务端必须真的拦住其他接口(否则"强制"只是前端的一跳)
        String token = textOf(login.body(), "token");
        Response blocked = get("/system/roles", token);
        assertThat(codeOf(blocked.body())).as("改密闸门必须真的生效").isEqualTo(40301);
    }

    @Test
    @DisplayName("刚设过密码的账号不会被要求改密(建号时也必须记改密时间)")
    void freshPasswordIsFine() throws Exception {
        Response login = post("/auth/login", loginBody(password), null);

        assertThat(login.body()).contains("\"mustChangePassword\":false");
        assertThat(codeOf(get("/system/roles", textOf(login.body(), "token")).body())).isZero();
    }

    // ——— 辅助 ———

    private void changePassword(String oldPassword, String newPassword) throws Exception {
        Response response = post("/auth/password", passwordBody(oldPassword, newPassword), login(oldPassword));
        assertThat(codeOf(response.body())).as("改密失败:%s", response.body()).isZero();
    }

    /** 把改密时间推到有效期之外(30 天),不用真的等。 */
    private void expirePassword() {
        asSuperUser(() -> {
            SysUser user = userRepository.findById(adminUserId).orElseThrow();
            user.setPwdUpdateTime(LocalDateTime.now().minusDays(40));
            userRepository.save(user);
            return null;
        });
    }

    private String passwordBody(String oldPassword, String newPassword) {
        return "{\"oldPassword\":\"" + oldPassword + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

    private String loginBody(String pwd) {
        return "{\"tenantCode\":\"" + tenantCode + "\",\"username\":\"" + username
                + "\",\"password\":\"" + pwd + "\",\"deviceId\":\"pwd-it\"}";
    }

    private String login(String pwd) throws Exception {
        Response response = post("/auth/login", loginBody(pwd), null);
        assertThat(codeOf(response.body())).as("登录失败:%s", response.body()).isZero();
        return textOf(response.body(), "token");
    }

    private Response post(String path, String body, String token) throws Exception {
        return send(HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), path, token);
    }

    private Response get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder().GET(), path, token);
    }

    private Response send(HttpRequest.Builder builder, String path, String token) throws Exception {
        builder.uri(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.body());
    }

    private int codeOf(String body) {
        Matcher matcher = Pattern.compile("\"code\":(\\d+)").matcher(body);
        assertThat(matcher.find()).as("响应体不含 code:%s", body).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private String textOf(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("响应体没有字段 %s:%s", field, body).isTrue();
        return matcher.group(1);
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, PLATFORM_ADMIN_USER_ID, "127.0.0.1", "pwd-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private record Response(String body) {
    }
}
