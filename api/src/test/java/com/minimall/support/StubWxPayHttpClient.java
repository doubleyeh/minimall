package com.minimall.support;

import com.minimall.mall.infra.pay.WxPayHttpClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 集成测试用的微信支付 HTTP 桩:按路径回罐头响应,全程不出网。
 *
 * <p>为什么整套集成测试都要换掉它:统一下单/退款都是真实出网调用,留着真实实现,
 * 用例要么打真实微信(不可能),要么因为网络异常一律返回空 → 支付链路整体走不通。
 * 报文组装与签名由 {@code WxPayClientTest} 用同样的桩单独断言,这里只关心流程能跑通。
 *
 * <p>标 {@code @Primary} 覆盖真实实现;并记录收到的请求,便于用例断言 notify_url 之类的细节。
 */
@Component
@Primary
public class StubWxPayHttpClient implements WxPayHttpClient {

    private final List<Request> requests = new ArrayList<>();

    @Override
    public WxPayHttpResult post(String url, Map<String, String> headers, String body) {
        requests.add(new Request(url, headers, body));
        if (url.contains("/v3/pay/transactions/jsapi")) {
            return new WxPayHttpResult(200, "{\"prepay_id\":\"stub-prepay-" + requests.size() + "\"}");
        }
        if (url.contains("/v3/refund/domestic/refunds")) {
            return new WxPayHttpResult(200, "{\"refund_id\":\"stub-refund-" + requests.size() + "\"}");
        }
        return new WxPayHttpResult(200, "{}");
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        requests.clear();
    }

    /** 一次出网请求的快照。 */
    public record Request(String url, Map<String, String> headers, String body) {
    }
}
