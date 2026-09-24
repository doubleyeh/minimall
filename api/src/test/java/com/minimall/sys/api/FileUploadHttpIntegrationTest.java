package com.minimall.sys.api;

import com.minimall.common.BusinessException;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.file.FileStorage;
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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件上传与访问(架构文档 7.5)。
 *
 * <p>钉住三类容易出事的点:
 * <ol>
 *   <li><b>往返可用</b>:传上去的字节,从访问地址拿回来必须一模一样,content-type 也得对
 *       (前端 img 靠它渲染)</li>
 *   <li><b>内容校验</b>:只看扩展名/Content-Type 是不够的(都是客户端说了算),
 *       所以把一段文本改名成 .png 必须被拒</li>
 *   <li><b>不能读到根目录外的东西</b>:key 是客户端可控的输入,{@code ../} 一类必须挡死</li>
 * </ol>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FileUploadHttpIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    private static final long FULL_PACKAGE_ID = 1L;
    private static final String BOUNDARY = "----miniMallTestBoundary";

    @LocalServerPort
    private int port;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private SysUserRepository userRepository;
    @Autowired
    private FileStorage fileStorage;
    @Autowired
    private StringRedisTemplate redis;

    private final HttpClient http = HttpClient.newHttpClient();
    private String tenantCode;
    private String username;
    private String password;

    @BeforeEach
    void setUpTenant() {
        redis.delete(redis.keys("*127.0.0.1*"));
        String code = "file" + suffix();
        TenantCreateResponse tenant = asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                code, "上传用例租户" + suffix(), FULL_PACKAGE_ID, null,
                "fileadmin" + suffix(), "用例管理员", null)));
        tenantCode = code;
        username = tenant.adminUsername();
        password = tenant.initialPassword();
        // 关掉强制改密:否则除改密/登出/刷新权限外一律 403,拿不到能调接口的令牌
        clearMustChangePassword(tenant.adminUserId());
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("上传再访问:拿回来的字节与 content-type 都要对")
    void uploadThenServe() throws Exception {
        byte[] png = realPng();
        String token = login();

        Response uploaded = postMultipart("/file-uploads", png, "goods.png", token);
        assertThat(bodyCodeOf(uploaded)).as("上传应当成功:%s", uploaded.body()).isZero();
        String url = textOf(uploaded.body(), "url");
        String key = textOf(uploaded.body(), "key");
        assertThat(key).as("key 里带租户与日期,便于排查与清理").matches("\\d+/\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{32}\\.png");

        Response served = get(url);
        assertThat(served.status()).isEqualTo(200);
        assertThat(served.contentType()).as("前端 img 靠它渲染").startsWith("image/png");
        assertThat(served.bodyBytes()).as("存进去什么,取出来就是什么").isEqualTo(png);
    }

    @Test
    @DisplayName("未登录不能上传(访问是公开的,上传不是)")
    void uploadRequiresLogin() throws Exception {
        assertThat(postMultipart("/file-uploads", realPng(), "a.png", null).status())
                .as("没有登录态时应当是 401,而不是默默存下一个文件")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("内容与图片格式不符:改名成 .png 的文本也要被拒")
    void rejectsContentThatIsNotAnImage() throws Exception {
        Response response = postMultipart("/file-uploads",
                "这其实是一段文本,只是扩展名是 png".getBytes(StandardCharsets.UTF_8), "fake.png", login());

        assertThat(bodyCodeOf(response)).as("只看扩展名就等于没校验").isEqualTo(40003);
        assertThat(response.body()).contains("文件内容与图片格式不符");
    }

    @Test
    @DisplayName("不支持的扩展名直接拒")
    void rejectsUnsupportedExtension() throws Exception {
        Response response = postMultipart("/file-uploads",
                "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8), "run.sh", login());

        assertThat(bodyCodeOf(response)).isEqualTo(40003);
    }

    @Test
    @DisplayName("key 里带 ../ 时不能读到存储根目录之外的文件")
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> fileStorage.load("../../pom.xml"))
                .as("key 是客户端可控输入,路径穿越必须挡死")
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fileStorage.load("/etc/passwd"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("超过大小上限的直接拒(在写盘之前)")
    void rejectsOversizedContent() throws Exception {
        long max = 5L * 1024 * 1024;
        byte[] huge = new byte[(int) max + 1];
        System.arraycopy(realPng(), 0, huge, 0, 8);

        assertThatThrownBy(() -> fileStorage.store("huge.png", huge))
                .isInstanceOf(BusinessException.class);
    }

    // ——— HTTP 与辅助 ———

    private String login() throws Exception {
        String body = "{\"tenantCode\":\"" + tenantCode + "\",\"username\":\"" + username
                + "\",\"password\":\"" + password + "\"}";
        Response response = send(HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), "/auth/login", null);
        assertThat(bodyCodeOf(response)).as("登录失败:%s", response.body()).isZero();
        return textOf(response.body(), "token");
    }

    /** 手工拼 multipart:项目里的用例都用 JDK HttpClient,不为这一个用例换测试脚手架。 */
    private Response postMultipart(String path, byte[] content, String filename, String token) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        return sendWithContentType(builder, path, token, "multipart/form-data; boundary=" + BOUNDARY);
    }

    private Response get(String path) throws Exception {
        return send(HttpRequest.newBuilder().GET(), path, null);
    }

    private Response send(HttpRequest.Builder builder, String path, String token) throws Exception {
        return sendWithContentType(builder, path, token, "application/json");
    }

    private Response sendWithContentType(HttpRequest.Builder builder, String path, String token, String contentType)
            throws Exception {
        builder.uri(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", contentType);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""),
                new String(response.body(), StandardCharsets.UTF_8),
                response.body());
    }

    private byte[] realPng() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.createGraphics().fillRect(0, 0, 4, 4);
        image.setRGB(0, 0, Color.RED.getRGB());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private int bodyCodeOf(Response response) {
        Matcher matcher = Pattern.compile("\"code\":(\\d+)").matcher(response.body());
        assertThat(matcher.find()).as("响应体不含 code:%s", response.body()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private String textOf(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("响应体里没有字段 %s:%s", field, body).isTrue();
        return matcher.group(1);
    }

    private void clearMustChangePassword(long userId) {
        asSuperUser(() -> {
            SysUser user = userRepository.findById(userId).orElseThrow();
            user.setMustChangePassword(0);
            userRepository.save(user);
            return null;
        });
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, PLATFORM_ADMIN_USER_ID, "127.0.0.1", "file-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private record Response(int status, String contentType, String body, byte[] bodyBytes) {
    }
}
