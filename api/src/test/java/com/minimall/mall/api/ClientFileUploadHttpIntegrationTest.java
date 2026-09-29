package com.minimall.mall.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 小程序侧的图片上传(`/mall/api/file-uploads`)。
 *
 * <p>后台端那条(`/file-uploads`)另有用例覆盖,这条是客户端的:评价晒图、售后凭证都靠它,
 * 而它此前一条用例都没有 —— 接口哪天被挪走或鉴权配错,小程序是跑不出来的。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ClientFileUploadHttpIntegrationTest {

    private static final String BOUNDARY = "----miniMallClientUpload";

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("小程序登录态可以上传,并且拿回来的地址能直接当 <image src> 用")
    void clientCanUploadAndServeBack() throws Exception {
        String token = clientLogin();
        byte[] png = realPng();

        Response uploaded = postMultipart(png, "review.png", token);
        assertThat(codeOf(uploaded.body())).as("上传应当成功:%s", uploaded.body()).isZero();
        String key = textOf(uploaded.body(), "key");
        String url = textOf(uploaded.body(), "url");
        assertThat(key).as("key 里带租户与日期,便于排查与清理").matches("\\d+/\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{32}\\.png");
        assertThat(url).as("业务表存的是 url,它必须指向刚存下的那个文件").endsWith(key);

        Response served = get(url);
        assertThat(served.status()).isEqualTo(200);
        assertThat(served.contentType()).startsWith("image/png");
        assertThat(served.bodyBytes()).as("存进去什么,取出来就是什么").isEqualTo(png);
    }

    @Test
    @DisplayName("没有小程序登录态不能上传")
    void uploadRequiresClientLogin() throws Exception {
        assertThat(postMultipart(realPng(), "a.png", null).status())
                .as("图片上传不能是免登录的入口")
                .isEqualTo(401);
    }

    // ——— HTTP 与辅助 ———

    private String clientLogin() throws Exception {
        Response login = send(HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"code\":\"upload-" + System.nanoTime() + "\"}", StandardCharsets.UTF_8)),
                "/mall/api/auth/wx-login", null, "application/json");
        assertThat(login.status()).as("小程序登录失败:%s", login.body()).isEqualTo(200);
        return textOf(login.body(), "token");
    }

    private Response postMultipart(byte[] content, String filename, String token) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return send(HttpRequest.newBuilder().POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())),
                "/mall/api/file-uploads", token, "multipart/form-data; boundary=" + BOUNDARY);
    }

    private Response get(String path) throws Exception {
        return send(HttpRequest.newBuilder().GET(), path, null, "application/json");
    }

    private Response send(HttpRequest.Builder builder, String path, String token, String contentType)
            throws Exception {
        builder.uri(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", contentType);
        // 客户端接口按租户隔离:不带这个头连客户都认不出来
        builder.header("X-Tenant-Code", "platform");
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

    private int codeOf(String body) {
        Matcher matcher = Pattern.compile("\"code\":(\\d+)").matcher(body);
        assertThat(matcher.find()).as("响应体不含 code:%s", body).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private String textOf(String body, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("响应体里没有字段 %s:%s", field, body).isTrue();
        return matcher.group(1);
    }

    private record Response(int status, String contentType, String body, byte[] bodyBytes) {
    }
}
