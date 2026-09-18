package com.minimall.infra.tenant;

import java.util.function.Supplier;

/**
 * 租户上下文(架构文档 4.1)。
 *
 * <p>持有当前线程的 {@code tenantId}(允许为空)与 {@code isSuperUser} 标志,基于 ThreadLocal。
 * 三种状态必须区分清楚,4.2 的过滤启用规则直接依赖它:
 * <ul>
 *   <li>未定租户:tenantId = null, superUser = false(登录、支付回调、健康检查)</li>
 *   <li>已定租户、未登录:tenantId 非空, superUser = false(带 X-Tenant-Code 的公开接口)</li>
 *   <li>已登录(含超管):tenantId 非空, superUser 见右</li>
 * </ul>
 *
 * <p><b>{@code isSuperUser} 是用户级,不是租户级</b>:它来自 {@code sys_user.is_super},
 * 不来自 {@code tenant.is_super}。挂在租户上会让该租户下所有普通用户一起跳过租户过滤(见 4.10 的说明)。
 *
 * <p>注意:本类不做任何 IO、不依赖 HTTP,所以测试里直接 set 注入就能验证依赖它的逻辑,
 * 不需要起真实请求——这是整套隔离机制可测试性的基础。
 *
 * <p>实现细节:用 ThreadLocal 而不是继承式上下文,是因为虚拟线程下 ThreadLocal 工作正常;
 * 但**跨线程不传播**,线程池场景必须在目标线程内部重新设置(6.3)。
 */
public final class TenantContext {

    private static final ThreadLocal<State> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 上下文快照。用于 runAsTenant 的嵌套恢复,也让"当前上下文"可以被安全地传递/断言。
     */
    public record State(Long tenantId, boolean superUser) {
    }

    public static void setTenantId(Long tenantId) {
        State current = HOLDER.get();
        HOLDER.set(new State(tenantId, current != null && current.superUser()));
    }

    public static Long getTenantId() {
        State current = HOLDER.get();
        return current == null ? null : current.tenantId();
    }

    public static void setSuperUser(boolean superUser) {
        State current = HOLDER.get();
        HOLDER.set(new State(current == null ? null : current.tenantId(), superUser));
    }

    public static boolean isSuperUser() {
        State current = HOLDER.get();
        return current != null && current.superUser();
    }

    /** 是否已经定位到租户。false 时租户表查询会被绑成恒假条件(4.2 的默认拒绝)。 */
    public static boolean hasTenant() {
        return getTenantId() != null;
    }

    /**
     * 请求/任务结束时<b>必须</b>调用,避免线程池复用导致串租户。
     */
    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 在指定租户上下文中执行一段逻辑,执行完自动恢复外层上下文(支持嵌套调用)。
     * 用于没有 HTTP 请求周期的场景:支付回调、定时任务、按租户循环的批量操作(第 6 节)。
     *
     * <p>**必须在目标线程内部调用**,不要在提交任务前的调用方线程里调(6.3)。
     */
    public static void runAsTenant(Long tenantId, boolean superUser, Runnable action) {
        State previous = HOLDER.get();
        try {
            HOLDER.set(new State(tenantId, superUser));
            action.run();
        } finally {
            restore(previous);
        }
    }

    /**
     * 带返回值的版本。文档只写了 Runnable,实际批量处理常需要拿到每个租户的结果,故补一个 Supplier 变体。
     */
    public static <T> T callAsTenant(Long tenantId, boolean superUser, Supplier<T> action) {
        State previous = HOLDER.get();
        try {
            HOLDER.set(new State(tenantId, superUser));
            return action.get();
        } finally {
            restore(previous);
        }
    }

    private static void restore(State previous) {
        if (previous == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(previous);
        }
    }
}
