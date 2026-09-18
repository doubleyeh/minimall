package com.minimall.infra.audit;

/**
 * 审计上下文快照(架构文档 4.12)。
 *
 * <p>要解决的问题:操作日志要异步落库,但异步线程里既没有 TenantContext 也没有 Sa-Token 会话,
 * 而 {@code sys_oper_log.tenant_id/user_id} 需要值。所以审计信息必须能被"带过去",而不是"现读"。
 *
 * <p>两个用途都不依赖"当前线程恰好有会话":
 * <ul>
 *   <li>HTTP 请求:{@code TenantWebFilter} 在入口构造一次并 {@link #bind} 到请求线程,
 *       4.4 的 JPA Auditing 从 {@link #current()} 取值</li>
 *   <li>异步场景:把本对象作为参数显式传给异步方法(或捕获进 lambda),在里面用它赋值
 *       {@code tenant_id}/{@code create_by} 后再 save</li>
 * </ul>
 *
 * <p><b>不要</b>指望在异步线程里重新推断身份:推断不出来,也没有必要(见 4.12 的关键约束)。
 */
public record AuditContext(Long tenantId, Long userId, String ip, String traceId) {

    /** 空快照:没有请求周期、也没显式传入时的兜底。 */
    public static final AuditContext EMPTY = new AuditContext(null, null, null, null);

    private static final ThreadLocal<AuditContext> HOLDER = new ThreadLocal<>();

    /**
     * 绑定到当前线程(HTTP 入口用)。请求结束必须 {@link #clear()},否则线程池复用会串身份。
     */
    public static void bind(AuditContext context) {
        if (context == null) {
            HOLDER.remove();
        } else {
            HOLDER.set(context);
        }
    }

    /**
     * 当前快照,永不返回 null(没有绑定时返回 {@link #EMPTY}),调用方不用到处判空。
     */
    public static AuditContext current() {
        AuditContext current = HOLDER.get();
        return current == null ? EMPTY : current;
    }

    public static void clear() {
        HOLDER.remove();
    }

    /**
     * 当前操作人。取不到就是 null——调用方要清楚:参与数据权限的业务表不允许写入 null 归属人(5.3),
     * 那种情况下必须显式构造带 userId 的快照传进来。
     */
    public static Long currentUserId() {
        return current().userId();
    }
}
