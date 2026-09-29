package com.minimall.mall.infra.pay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 微信支付的默认 HTTP 实现。
 *
 * <p>用静态 {@code RestClient.builder()} 而不是注入 {@code RestClient.Builder}:Boot 4 把
 * restclient 的自动配置拆到了独立模块,容器里没有那个 Builder,注入会在启动时失败。
 * {@code JdkClientHttpRequestFactory} 用来给连接与读都设上超时 —— 外部调用不能有无限等待。
 *
 * <p>4xx/5xx 与网络异常都不抛,原样回状态码与原文:由 {@code WxPayClient} 决定怎么翻译。
 */
@Component
public class RestClientWxPayHttpClient implements WxPayHttpClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientWxPayHttpClient.class);

    private final RestClient restClient;

    public RestClientWxPayHttpClient(WxPayProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSecondsOrDefault()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(properties.readTimeoutSecondsOrDefault()));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public WxPayHttpResult post(String url, Map<String, String> headers, String body) {
        try {
            return restClient.post()
                    .uri(URI.create(url))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(target -> headers.forEach(target::add))
                    .body(body == null ? "" : body)
                    .exchange((request, response) -> new WxPayHttpResult(
                            response.getStatusCode().value(), readBody(response)), true);
        } catch (RuntimeException ex) {
            log.error("调用微信支付失败 url={}", url, ex);
            return new WxPayHttpResult(0, "");
        }
    }

    @Override
    public WxPayHttpResult get(String url, Map<String, String> headers) {
        try {
            return restClient.get()
                    .uri(URI.create(url))
                    .headers(target -> headers.forEach(target::add))
                    .exchange((request, response) -> new WxPayHttpResult(
                            response.getStatusCode().value(), readBody(response)), true);
        } catch (RuntimeException ex) {
            log.error("调用微信支付失败 url={}", url, ex);
            return new WxPayHttpResult(0, "");
        }
    }

    private String readBody(org.springframework.http.client.ClientHttpResponse response) {
        try {
            byte[] bytes = response.getBody().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }
}
