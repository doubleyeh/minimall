package com.minimall.sys.api;

import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登录验证码(架构文档 7.1.1)。
 *
 * <p>钉住三条契约:
 * <ol>
 *   <li><b>不强制每次登录都要验证码</b>:失败次数没到阈值时,不带验证码照常能登 ——
 *       这是所有既有调用方(以及小程序端)不会被这次改动打断的前提</li>
 *   <li><b>失败到阈值后必须带</b>,且带了就必须对</li>
 *   <li><b>一次性</b>:同一个验证码不能用第二次,否则"一次性"就等于没有</li>
 * </ol>
 *
 * <p>用例自己建一个租户来试,**不动种子超管账号**:连续失败会写它的 login_fail_count,
 * 而别的用例还要用它登录,污染了就是一片莫名其妙的失败。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CaptchaHttpIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    private static final long FULL_PACKAGE_ID = 1L;
    private static final Pattern CAPTCHA_ID = Pattern.compile("\"captchaId\":\"([^\"]+)\"");

    @LocalServerPort
    private int port;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private com.minimall.infra.security.LoginProperties loginProperties;

    private final HttpClient http = HttpClient.newHttpClient();
    private String tenantCode;
    private String username;
    private String password;

    @BeforeEach
    void setUpTenant() {
        // 登录 IP 限流按 IP 计数,本用例登录次数多,不清计数器会莫名其妙拿到 429
        redis.delete(redis.keys("*127.0.0.1*"));
        String code = "cap" + suffix();
        String admin = "capadmin" + suffix();
        TenantCreateResponse tenant = asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                code, "验证码用例租户" + suffix(), FULL_PACKAGE_ID, null, admin, "用例管理员", null)));
        tenantCode = code;
        username = tenant.adminUsername();
        password = tenant.initialPassword();
    }

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("取验证码:返回可直接展示的 PNG data URL,且图里真的画了东西")
    void returnsDisplayableImage() throws Exception {
        Response response = get("/auth/captcha");

        assertThat(response.status()).as("取验证码是白名单接口,不需要登录态").isEqualTo(200);
        assertThat(response.body()).contains("\"captchaId\":");
        assertThat(response.body()).as("前端直接放进 img 的 src,不用再拼前缀")
                .contains("\"image\":\"data:image/png;base64,");
        assertThat(countDarkPixels(imageBase64Of(response.body())))
                .as("无头环境缺字体时 AWT 会静默画出全白图,人就永远登不进来了")
                .isGreaterThan(50);
    }

    @Test
    @DisplayName("失败次数没到阈值:不带验证码也能登录(既有调用方不受影响)")
    void captchaNotRequiredBeforeThreshold() throws Exception {
        Response response = login(loginBody(null, null));

        assertThat(bodyCodeOf(response)).as("新账号失败次数为 0,不该被验证码拦住").isZero();
        assertThat(response.body()).contains("\"token\":");
    }

    @Test
    @DisplayName("验证码必须对:错的直接拒绝,对的放行")
    void wrongCaptchaIsRejected() throws Exception {
        Response wrong = login(loginBody(fetchCaptchaId(), "!!!!"));

        assertThat(bodyCodeOf(wrong)).as("验证码不正确 → 40005").isEqualTo(40005);
        assertThat(wrong.body()).doesNotContain("\"token\":");

        String captchaId = fetchCaptchaId();
        Response right = login(loginBody(captchaId, currentAnswer(captchaId)));

        assertThat(bodyCodeOf(right)).as("验证码正确 → 正常登录").isZero();
    }

    @Test
    @DisplayName("验证码是一次性的:同一个再用一次会被拒")
    void captchaIsSingleUse() throws Exception {
        String captchaId = fetchCaptchaId();
        String answer = currentAnswer(captchaId);

        assertThat(bodyCodeOf(login(loginBody(captchaId, answer)))).isZero();
        assertThat(bodyCodeOf(login(loginBody(captchaId, answer))))
                .as("用过即失效,否则同一个人可以拿它试到对为止")
                .isEqualTo(40005);
    }

    @Test
    @DisplayName("失败到阈值后:不带验证码被拒(40004),带上正确的就能过")
    void captchaRequiredAfterFailures() throws Exception {
        for (int i = 0; i < loginProperties.captchaAfterFailures(); i++) {
            login(loginBodyWithPassword("wrong-password-" + i));
        }

        Response withoutCaptcha = login(loginBody(null, null));
        assertThat(bodyCodeOf(withoutCaptcha)).as("到阈值后必须带验证码 → 40004").isEqualTo(40004);

        String captchaId = fetchCaptchaId();
        Response withCaptcha = login(loginBody(captchaId, currentAnswer(captchaId)));
        assertThat(bodyCodeOf(withCaptcha)).as("带对了就该放行").isZero();
        assertThat(withCaptcha.body()).contains("\"token\":");
    }

    // ——— HTTP 与辅助 ———

    private String loginBody(String captchaId, String captchaCode) {
        StringBuilder body = new StringBuilder("{\"tenantCode\":\"").append(tenantCode)
                .append("\",\"username\":\"").append(username)
                .append("\",\"password\":\"").append(password).append("\"");
        if (captchaId != null) {
            body.append(",\"captchaId\":\"").append(captchaId).append("\"");
        }
        if (captchaCode != null) {
            body.append(",\"captchaCode\":\"").append(captchaCode).append("\"");
        }
        return body.append("}").toString();
    }

    /** 用错误的密码登录(不带验证码),用来把失败次数推过阈值。 */
    private String loginBodyWithPassword(String pwd) {
        return "{\"tenantCode\":\"" + tenantCode + "\",\"username\":\"" + username
                + "\",\"password\":\"" + pwd + "\"}";
    }

    /** 取一张验证码并返回它的 id;答案只在服务端,需要时用 {@link #currentAnswer} 读出来。 */
    private String fetchCaptchaId() throws Exception {
        Matcher matcher = CAPTCHA_ID.matcher(get("/auth/captcha").body());
        assertThat(matcher.find()).as("响应里应当有 captchaId").isTrue();
        return matcher.group(1);
    }

    /** 直接读 Redis 里的答案 —— 真实用户是从图里读的,测试没这个能力,只能白盒取。 */
    private String currentAnswer(String captchaId) {
        String answer = redis.opsForValue().get("captcha:" + captchaId);
        assertThat(answer).as("验证码答案必须在服务端(Redis)而不是图里").isNotNull();
        return answer;
    }

    private int bodyCodeOf(Response response) {
        Matcher matcher = Pattern.compile("\"code\":(\\d+)").matcher(response.body());
        assertThat(matcher.find()).as("响应体应当有 code 字段:%s", response.body()).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private String imageBase64Of(String body) {
        Matcher matcher = Pattern.compile("\"image\":\"data:image/png;base64,([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("响应里应当有 PNG 的 data URL").isTrue();
        return matcher.group(1);
    }

    /** 数一下"深色"像素:文字与干扰线是深色,背景是白色。 */
    private int countDarkPixels(String base64) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        assertThat(image).as("base64 必须能解出一张真图").isNotNull();
        int dark = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) < 0x999999) {
                    dark++;
                }
            }
        }
        return dark;
    }

    private Response get(String path) throws Exception {
        return send(HttpRequest.newBuilder().GET(), path);
    }

    private Response login(String jsonBody) throws Exception {
        return send(HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)), "/auth/login");
    }

    private Response send(HttpRequest.Builder builder, String path) throws Exception {
        builder.uri(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, PLATFORM_ADMIN_USER_ID, "127.0.0.1", "captcha-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private record Response(int status, String body) {
    }
}
