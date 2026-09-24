package com.minimall.mall.infra.pay;

import java.util.Map;

/**
 * 微信支付的 HTTP 边界。
 *
 * <p>切在这一层是为了可测:桩替换掉它之后,既能断言发出去的 URL/头/报文,又能用罐头响应断言解析,
 * 全程不发真实网络请求。
 */
public interface WxPayHttpClient {

    /**
     * @param status HTTP 状态码;0 表示请求没发出去(网络异常)
     * @param body   响应原文,失败时可能为空
     */
    WxPayHttpResult post(String url, Map<String, String> headers, String body);

    record WxPayHttpResult(int status, String body) {
    }
}
