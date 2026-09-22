package com.minimall.mall.infra.pay;

import com.minimall.mall.infra.auth.ClientTokenProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信支付网关的**非 mock 分支**(商城设计文档 3.3、3.8)。
 *
 * <p>测试跑在 {@code wx-mock=true} 上,所以 mock 分支被"下单 → 支付 → 发货"那条主链路覆盖了,
 * 而"渠道未接"这三条分支从来没有被执行过。它们的语义必须单独钉住:
 * **未接渠道时不能抛异常,要返回空** —— 下层拿到空值会翻译成"拉起支付失败 / 退款申请失败"
 * 的业务语义;抛出去则变成 500,用户看到的是系统故障。
 *
 * <p>这里不启动 Spring、不发真实网络请求:非 mock 分支当前是 TODO 占位,直接返回空值。
 * 等真实渠道接入后,这几条断言应当换成"签名失败 / 验签不通过"的场景。
 */
class WxPayClientTest {

    /** wxMock = false:即"配置了 appId/secret 但没有接入真实渠道"的状态。 */
    private final WxPayClient client =
            new WxPayClient(new ClientTokenProperties("unit-test-secret", 30, "appid", "secret", false));

    @Test
    @DisplayName("未接渠道:统一下单返回空而不是抛异常(上层会翻译成拉起支付失败)")
    void unifiedOrderReturnsEmptyWhenChannelAbsent() {
        assertThat(client.unifiedOrder("out-1", new BigDecimal("60.00"), "openid", "商城订单 out-1"))
                .isEmpty();
    }

    @Test
    @DisplayName("未接渠道:回调验签一律不通过(宁可拒收,也不能默认放行)")
    void verifyCallbackRejectsWhenChannelAbsent() {
        assertThat(client.verifyCallback("signature", "{}")).isFalse();
    }

    @Test
    @DisplayName("未接渠道:退款申请返回空(上层会把退款记录留在申请中,等回调更新)")
    void refundReturnsEmptyWhenChannelAbsent() {
        assertThat(client.refund("out-1", "refund-1", new BigDecimal("60.00"))).isEmpty();
    }

    @Test
    @DisplayName("mock 分支:预支付单号取自商户单号,验签放行,退款给出单号")
    void mockBranchKeepsWorking() {
        WxPayClient mock = new WxPayClient(
                new ClientTokenProperties("unit-test-secret", 30, "appid", "secret", true));

        Optional<WxPayClient.PrepayResult> result =
                mock.unifiedOrder("out-2", new BigDecimal("60.00"), null, "商城订单 out-2");
        assertThat(result).isPresent();
        assertThat(result.orElseThrow().prepayId()).isEqualTo("mock-prepay-out-2");
        assertThat(result.orElseThrow().payParams().packageValue()).isEqualTo("prepay_id=mock-prepay-out-2");
        assertThat(result.orElseThrow().payParams().signType()).isEqualTo("RSA");

        assertThat(mock.verifyCallback("signature", "{}")).isTrue();
        assertThat(mock.refund("out-2", "refund-2", BigDecimal.TEN)).contains("mock-refund-refund-2");
    }
}
