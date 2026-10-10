package com.minimall.infra.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private static ClientIpResolver resolver(String... proxies) {
        return new ClientIpResolver(new TrustedProxyProperties(List.of(proxies)));
    }

    @Test
    @DisplayName("不可信来源带来的 XFF 一律不采信:限流键和审计 IP 都不能由客户端决定")
    void ignoresForwardedFromUntrustedPeer() {
        ClientIpResolver resolver = resolver("127.0.0.1");
        assertThat(resolver.resolve("203.0.113.9", "8.8.8.8")).isEqualTo("203.0.113.9");
        assertThat(resolver.resolve("203.0.113.9", "1.1.1.1, 2.2.2.2")).isEqualTo("203.0.113.9");
    }

    @Test
    @DisplayName("可信代理后面:取 XFF 里最右侧的不可信地址")
    void takesRightmostUntrustedHop() {
        ClientIpResolver resolver = resolver("127.0.0.1", "10.0.0.0/8");
        // nginx 覆盖写时只有一个值
        assertThat(resolver.resolve("127.0.0.1", "198.51.100.7")).isEqualTo("198.51.100.7");
        // 多级代理:最右是代理自己,跳过它
        assertThat(resolver.resolve("127.0.0.1", "198.51.100.7, 10.0.0.5")).isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("可信代理但没带 XFF:用对端地址")
    void fallsBackToRemoteAddr() {
        assertThat(resolver("127.0.0.1").resolve("127.0.0.1", null)).isEqualTo("127.0.0.1");
        assertThat(resolver("127.0.0.1").resolve("127.0.0.1", "  ")).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("XFF 全是可信代理时退回对端,不会把代理地址当成客户端")
    void skipsAllTrustedHops() {
        assertThat(resolver("127.0.0.1", "10.0.0.0/8").resolve("127.0.0.1", "10.0.0.5, 10.0.0.6"))
                .isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("不配可信代理:任何来源都不采信 XFF")
    void emptyTrustListNeverTrustsForwarded() {
        ClientIpResolver resolver = new ClientIpResolver(new TrustedProxyProperties(null));
        assertThat(resolver.resolve("127.0.0.1", "198.51.100.7")).isEqualTo("127.0.0.1");
    }
}
