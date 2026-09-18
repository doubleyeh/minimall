package com.minimall.infra.tenant;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 租户/数据权限过滤切面(架构文档 4.2)。
 *
 * <p>它覆盖的是 {@code TenantWebFilter} 覆盖不到的那条路径:异步任务、支付回调、定时任务。
 * 这些场景每次进入事务方法都可能是**新的 Session**,必须在每个事务边界重新 enable,
 * 否则 TenantContext 里租户设对了、Hibernate 层却没生效,过滤形同虚设。
 *
 * <p>{@code @Order(10)} 必须大于 {@link com.minimall.infra.config.TransactionConfig} 里
 * 事务 advisor 的 order(0),保证本切面在事务边界**内侧**执行。改这个数字之前先读 4.2 的说明。
 *
 * <p>切点用注解匹配({@code @annotation(Transactional)})而不是包名通配,避免切到不该切的方法。
 * 由此推出一条硬约束:**异步入口方法必须标注 {@code @Transactional}** ——
 * 没有事务就没有 Session 边界,过滤器无处可挂。
 */
@Aspect
@Component
@Order(10)
public class TenantFilterAspect {

    private final TenantFilterService tenantFilterService;

    /**
     * 注入的是共享的、事务作用域的代理:在事务内 unwrap 得到的就是当前事务绑定的 Session。
     * 不要换成 EntityManagerFactory 自己 new 一个 EntityManager——那会脱离事务。
     */
    @PersistenceContext
    private EntityManager entityManager;

    public TenantFilterAspect(TenantFilterService tenantFilterService) {
        this.tenantFilterService = tenantFilterService;
    }

    /**
     * 切点同时覆盖**方法级**与**类级** {@code @Transactional}。
     *
     * <p>为什么必须带上类级({@code @within}):本方案要求 service 实现类统一在类上标注
     * {@code @Transactional}(而不是逐个方法标注),那才是"每个入口都有事务边界"的可检查不变量;
     * 只看方法级注解会漏掉所有只在类上标注的入口 —— 而漏掉的后果不是报错,是**过滤器没启用、
     * 查询直接跨租户**,是最严重的那类静默故障。
     *
     * <p>顺带说明为什么启用点不放在 servlet filter 里:servlet filter 阶段
     * {@code OpenEntityManagerInViewInterceptor} 还没执行,请求上根本没有绑定 Session,
     * 在那里 enable 的过滤器会被随后创建的 Session 覆盖掉。所以 HTTP 路径的启用也走这里,
     * servlet filter 只负责"识别租户"(见 {@code TenantWebFilter})。
     */
    @Around("@annotation(org.springframework.transaction.annotation.Transactional)"
            + " || @within(org.springframework.transaction.annotation.Transactional)")
    public Object applyFiltersAroundTransaction(ProceedingJoinPoint joinPoint) throws Throwable {
        tenantFilterService.apply(entityManager);
        return joinPoint.proceed();
    }
}
