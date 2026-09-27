package com.minimall.sys.api;

import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.api.dto.UserSaveRequest;
import com.minimall.sys.domain.SysOperLog;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.repository.SysOperLogRepository;
import com.minimall.sys.domain.repository.SysUserRepository;
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
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 操作日志导出(架构文档 7.6)。
 *
 * <p>响应体是**文件字节而不是统一响应体**,所以这里断言的是 HTTP 层的东西:状态码、content-type、
 * Content-Disposition 文件名、BOM、以及内容里真的有那一行。这些错一个的表现分别是:
 * 前端下载到一个 JSON、文件名是 undefined、Excel 打开乱码、导出内容缺行。
 *
 * <p>权限侧不在这里验:{@code AdminPermissionHttpTest} 从路由表穷举 /system/** 时已经覆盖了
 * "必须带权限码 + 无权限账号真实被拒"。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OperLogExportHttpIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    private static final String ADMIN_PASSWORD = "Export@123456";

    @LocalServerPort
    private int port;
    @Autowired
    private UserService userService;
    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private SysOperLogRepository operLogRepository;
    @Autowired
    private StringRedisTemplate redis;

    private final HttpClient http = HttpClient.newHttpClient();
    private String username;

    @BeforeEach
    void setUpSuperUser() {
        redis.delete(redis.keys("*127.0.0.1*"));
        username = "expadmin" + suffix();
        // 平台超管:日志是平台级菜单(租户角色永远拿不到 system:operlog:list),只有超管能导出。
        // is_super 接口不暴露,按 4.10 只能直接改库(与 AdminPermissionHttpTest 同一手法)
        asSuperUser(() -> {
            userService.create(new UserSaveRequest(username, ADMIN_PASSWORD, "导出用例超管",
                    null, null, List.of(), 1));
            SysUser user = userRepository.findByTenantIdAndUsername(PLATFORM_TENANT_ID, username).orElseThrow();
            user.setIsSuper(1);
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
    @DisplayName("导出:是文件不是 JSON,带 BOM 与文件名,内容里真有那一行")
    void exportsAsCsvFile() throws Exception {
        String module = "导出用例" + suffix();
        saveLog(module);
        Response response = export("?module=" + module, login());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).as("前端据此判断拿到的是文件还是错误 JSON")
                .startsWith("text/csv");
        assertThat(response.contentDisposition()).contains("attachment").contains(".csv");
        assertThat(response.body()).as("缺 BOM 的话 Excel 打开中文是乱码").startsWith("\uFEFF");
        assertThat(response.body()).as("表头要让拿到文件的人看得懂").contains("时间,租户ID,用户ID,模块");
        assertThat(response.body()).contains(module);
    }

    @Test
    @DisplayName("导出要登录:未登录时 401,而不是默默导出一份全平台日志")
    void exportRequiresLogin() throws Exception {
        assertThat(export("", null).status()).isEqualTo(401);
    }

    // ——— 辅助 ———

    private Response export(String queryString, String token) throws Exception {
        return get("/system/oper-logs/export" + queryString, token);
    }

    private String login() throws Exception {
        String body = "{\"tenantCode\":\"platform\",\"username\":\"" + username
                + "\",\"password\":\"" + ADMIN_PASSWORD + "\",\"deviceId\":\"export-it\"}";
        Response response = send(HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), "/auth/login", null);
        Matcher matcher = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.body());
        assertThat(matcher.find()).as("登录失败:%s", response.body()).isTrue();
        return matcher.group(1);
    }

    private void saveLog(String module) {
        asSuperUser(() -> {
            SysOperLog log = new SysOperLog();
            log.setTenantId(PLATFORM_TENANT_ID);
            log.setUserId(PLATFORM_ADMIN_USER_ID);
            log.setModule(module);
            log.setPermCode("system:operlog:list");
            log.setMethod("GET /system/oper-logs");
            log.setStatus(1);
            log.setIp("127.0.0.1");
            log.setTraceId("trace-" + suffix());
            operLogRepository.save(log);
            return null;
        });
    }

    private Response get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder().GET(), path, token);
    }

    private Response send(HttpRequest.Builder builder, String path, String token) throws Exception {
        builder.uri(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""),
                response.headers().firstValue("Content-Disposition").orElse(""),
                new String(response.body(), StandardCharsets.UTF_8));
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, PLATFORM_ADMIN_USER_ID, "127.0.0.1", "export-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private record Response(int status, String contentType, String contentDisposition, String body) {
    }
}
