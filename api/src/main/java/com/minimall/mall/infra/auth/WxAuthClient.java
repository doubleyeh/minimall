package com.minimall.mall.infra.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/**
 * 微信登录凭证换取 openid(商城设计文档 3.1 第 1 步:code2Session)。
 *
 * <p>{@code mall.auth.wx-mock = true} 时走模拟实现:直接把 {@code code} 当作 openid 的一部分
 * (形如 {@code mock-openid-<code>})。**这是给本地开发与自动化测试用的** ——
 * 没有它,任何涉及登录的用例都必须依赖真实微信服务,CI 里根本跑不起来。
 * 生产环境必须保持 {@code false},否则任何人编一个 code 就能登录成任意客户。
 */
@Component
public class WxAuthClient {

    private static final Logger log = LoggerFactory.getLogger(WxAuthClient.class);

    private static final String CODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";

    private final ClientTokenProperties properties;
    /**
     * 直接 {@code create()} 而不是注入 {@code RestClient.Builder}:Boot 4 把 RestClient 的自动配置
     * 拆到了独立模块,项目没有引入时容器里根本没有那个 Builder —— 注入式写法会在启动时直接失败。
     * 这里只需要一个最简单的 HTTP 客户端(调用微信接口),不需要 Spring 的转换器/拦截器定制。
     */
    private final RestClient restClient = RestClient.create();

    public WxAuthClient(ClientTokenProperties properties) {
        this.properties = properties;
    }

    /**
     * 用小程序 {@code wx.login()} 返回的 code 换取会话信息。
     *
     * <p>换不到(微信返回 errcode)时返回空,由调用方统一按"登录失败"处理 ——
     * 不要把微信的错误文案透给端上,那些信息对用户没有意义,对排查也无用(真正的排查要看服务端日志)。
     */
    public Optional<WxSession> code2Session(String code) {
        if (properties.wxMockEnabled()) {
            log.info("微信 mock 模式已开启,code={} 直接映射为模拟 openid(mall.auth.wx-mock=true)", code);
            return Optional.of(new WxSession("mock-openid-" + code, null));
        }
        try {
            Map<?, ?> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder.scheme("https").host("api.weixin.qq.com")
                            .path("/sns/jscode2session")
                            .queryParam("appid", properties.wxAppId())
                            .queryParam("secret", properties.wxAppSecret())
                            .queryParam("js_code", code)
                            .queryParam("grant_type", "authorization_code")
                            .build())
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("openid") == null) {
                log.warn("code2Session 未返回 openid: {}", response == null ? "null" : response.get("errmsg"));
                return Optional.empty();
            }
            Object unionid = response.get("unionid");
            return Optional.of(new WxSession(String.valueOf(response.get("openid")),
                    unionid == null ? null : String.valueOf(unionid)));
        } catch (RuntimeException ex) {
            // 微信侧超时/网络异常:按登录失败处理并记录,不要把它变成 500 —— 用户看到的应该是"登录失败,请重试"
            log.error("调用 code2Session 失败", ex);
            return Optional.empty();
        }
    }

    /** 微信会话信息。 */
    public record WxSession(String openid, String unionid) {
    }

    /** 供日志/文档引用,避免魔法字符串散落。 */
    public static String code2SessionUrl() {
        return CODE2SESSION_URL;
    }
}
