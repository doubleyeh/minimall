package com.minimall.mall.infra.auth;

import java.util.function.Supplier;

/**
 * 客户端身份上下文(ThreadLocal)。
 *
 * <p>与 {@link com.minimall.infra.tenant.TenantContext} 的关系:两者**同时存在但职责不同** ——
 * 租户上下文由客户端过滤器一并设置(这样租户过滤器和后台链路是同一套机制),
 * 这里额外保存 {@code customerId},供"只能操作自己的数据"这类判断使用。
 *
 * <p>必须在请求结束时清理:线程池/虚拟线程复用会让下一个请求串上这次的客户身份(4.1、4.12 的同一条教训)。
 * {@code ClientAuthFilter} 在 {@code finally} 里保证这一点。
 */
public final class ClientContext {

    private static final ThreadLocal<ClientPrincipal> CURRENT = new ThreadLocal<>();

    private ClientContext() {
    }

    public static void set(ClientPrincipal principal) {
        CURRENT.set(principal);
    }

    public static ClientPrincipal current() {
        return CURRENT.get();
    }

    /** 当前客户 ID;未登录(或后台链路)时为 null。 */
    public static Long getCustomerId() {
        ClientPrincipal principal = CURRENT.get();
        return principal == null ? null : principal.customerId();
    }

    /**
     * 取当前客户 ID,取不到直接抛异常。
     *
     * <p>用于"必须有客户身份"的业务入口(购物车、订单、售后)。**不要在这类入口用
     * {@link #getCustomerId()} 然后判空后静默返回空列表** —— 那会把"没登录"伪装成"没数据",
     * 排查时看不出是认证问题还是业务问题。
     */
    public static Long requireCustomerId() {
        Long customerId = getCustomerId();
        if (customerId == null) {
            throw new IllegalStateException("当前请求没有客户端身份,该接口必须经过 ClientAuthFilter(见 mall 3.1)");
        }
        return customerId;
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 在指定身份下执行(供异步/测试使用,语义同 {@code TenantContext.runAsTenant})。 */
    public static <T> T callAs(ClientPrincipal principal, Supplier<T> action) {
        ClientPrincipal previous = CURRENT.get();
        CURRENT.set(principal);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
