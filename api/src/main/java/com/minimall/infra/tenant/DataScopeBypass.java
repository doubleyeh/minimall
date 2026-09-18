package com.minimall.infra.tenant;

import java.util.function.Supplier;

/**
 * "本轮事务不要启用数据权限过滤"的显式开关(架构文档 5.3 的边界)。
 *
 * <p><b>它解决的是哪一类问题</b>:数据权限的输入是"当前身份"({@code AuditContext} 里的 userId)。
 * 而登录、刷新令牌换票这两个入口**恰恰是在身份确认之前**执行的:此时 userId 还是 null,
 * {@code DataScopeProvider} 按"算不出来就拒绝"的规矩返回 {@code denyAll},
 * 于是紧接着那句 {@code select ... from sys_user where tenant_id=? and username=?} 被拦成空集,
 * 结果是**账号密码都正确却登录失败**。
 *
 * <p><b>为什么用 ThreadLocal 而不是给方法加注解</b>:过滤器的启用发生在**每一个**事务边界
 * (见 {@code TenantFilterAspect}),登录过程中会嵌套调用多个 {@code @Transactional} 方法。
 * 若用方法注解,只有被标注的那个边界会跳过,嵌套进去的子调用又会把 denyAll 重新启用;
 * ThreadLocal 是"包住整段逻辑"的语义,嵌套边界读到的都是同一个值。
 *
 * <p><b>使用约束(务必遵守)</b>:只允许"读取身份本身"的场景使用,当前仅两处:
 * 登录入口与刷新令牌换票。**业务查询绝不允许调用** —— 那等于把数据权限整条防线关掉。
 * 需要平台级跨租户访问时,用超管上下文({@code TenantContext.callAsTenant(..., true, ...)})表达,
 * 而不是这个开关。
 *
 * <p>它只影响数据权限过滤,**租户过滤在任何情况下都照常生效**(4.1):豁免的粒度是"看谁的数据",
 * 不是"看哪个租户的数据"。
 */
public final class DataScopeBypass {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private DataScopeBypass() {
    }

    /** 当前线程是否处于"身份前置查询"范围。{@code TenantFilterService} 会读它。 */
    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /**
     * 在跳过数据权限过滤的前提下执行一段逻辑。
     *
     * <p>用 {@code finally} 恢复**进入前的值**而不是直接 remove:支持嵌套调用时不会提前把外层范围关掉。
     */
    public static <T> T run(Supplier<T> action) {
        Boolean previous = ACTIVE.get();
        ACTIVE.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
        }
    }
}
