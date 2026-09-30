package com.minimall.support;

import com.minimall.mall.infra.pay.WxPayHttpClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * 出网应答里的单号序列:**只增不减、且是类级的**,{@link #reset()} 不能碰它。
     *
     * <p>以前按 {@code requests.size()} 发号,而 reset 会清空请求列表 —— 下一个用例又从 1 开始,
     * 撞上库里前一个用例留下的行。单号在库上有唯一键(payment 的 {@code uk_wx_transaction}、
     * refund 的 {@code uk_wx_refund}),撞了就是"写回微信单号失败",表现成随机失败的用例。
     *
     * <p>必须是 {@code static}:跑 HTTP 用例的那些类用了另一套 webEnvironment,Spring 会再建一个
     * 上下文,于是本桩有两个实例 —— 实例级序列会各自从 1 数起,照样撞。
     * 每次运行开始时会重置数据库(见 {@code TestDatabaseReset}),所以只增序列在一个运行内足够唯一。
     */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** 查单返回的 {@code trade_state};用例按需设成 SUCCESS / NOTPAY / CLOSED。 */
    private String queryTradeState = "NOTPAY";

    /** 让退款接口返回非 200(模拟网络/渠道故障)。 */
    private boolean refundRejected;

    /** 让退款接口返回 200 但不带 refund_id(渠道没受理)。 */
    private boolean refundWithoutId;

    public void setQueryTradeState(String queryTradeState) {
        this.queryTradeState = queryTradeState;
    }

    public void setRefundRejected(boolean refundRejected) {
        this.refundRejected = refundRejected;
    }

    public void setRefundWithoutId(boolean refundWithoutId) {
        this.refundWithoutId = refundWithoutId;
    }

    @Override
    public WxPayHttpResult post(String url, Map<String, String> headers, String body) {
        requests.add(new Request(url, headers, body));
        if (url.contains("/v3/pay/transactions/jsapi")) {
            return new WxPayHttpResult(200, "{\"prepay_id\":\"stub-prepay-" + SEQUENCE.incrementAndGet() + "\"}");
        }
        if (url.contains("/v3/refund/domestic/refunds")) {
            if (refundRejected) {
                return new WxPayHttpResult(500, "{\"code\":\"SYSTEM_ERROR\"}");
            }
            if (refundWithoutId) {
                return new WxPayHttpResult(200, "{}");
            }
            return new WxPayHttpResult(200, "{\"refund_id\":\"stub-refund-" + SEQUENCE.incrementAndGet() + "\"}");
        }
        return new WxPayHttpResult(200, "{}");
    }

    @Override
    public WxPayHttpResult get(String url, Map<String, String> headers) {
        requests.add(new Request(url, headers, null));
        if (url.contains("/v3/pay/transactions/out-trade-no/")) {
            return new WxPayHttpResult(200, "{\"trade_state\":\"" + queryTradeState
                    + "\",\"transaction_id\":\"stub-txn-" + SEQUENCE.incrementAndGet() + "\"}");
        }
        return new WxPayHttpResult(200, "{}");
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        requests.clear();
        queryTradeState = "NOTPAY";
        refundRejected = false;
        refundWithoutId = false;
    }

    /** 一次出网请求的快照。 */
    public record Request(String url, Map<String, String> headers, String body) {
    }
}
