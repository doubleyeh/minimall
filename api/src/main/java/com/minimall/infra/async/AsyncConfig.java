package com.minimall.infra.async;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步执行器配置(架构文档 1.2、6.3、7.2)。
 *
 * <p>三件事:
 * <ol>
 *   <li>给 {@code @Async} 一个**明确的**执行器,而不是用默认那个(默认执行器没有 TaskDecorator,
 *       traceId 到了异步线程就断了)</li>
 *   <li>挂上 {@link ContextPropagatingTaskDecorator},把 MDC 带过线程边界</li>
 *   <li>用**虚拟线程**执行异步任务,与 Tomcat 的配置保持一致(9.3 里 {@code spring.threads.virtual.enabled}
 *       已经打开,这里显式对齐)。注意虚拟线程下同样不能跨线程传播 ThreadLocal,所以第 2 条不能省;
 *       另外 1.2 的约束照旧:不要用 {@code synchronized},用 {@code ReentrantLock}</li>
 * </ol>
 *
 * <p><b>提交异步任务时的两条硬约束</b>(见 6.3,不满足就等于隔离失效):
 * ①异步入口方法必须标注 {@code @Transactional} —— 没有事务边界就没有 Session,
 * {@code TenantFilterAspect} 没地方 enable 过滤器;②租户上下文必须在**任务内部**用
 * {@code runAsTenant} 建立,审计快照(4.12)必须当参数传进去,不要在异步线程里读 Sa-Token 会话。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Bean 名用 {@code applicationTaskExecutor}:这是 Spring Boot 对 {@code @Async} 的约定名,
     * 定义它之后 Boot 的自动配置会让位,不会有第二个执行器来抢。
     *
     * <p>刻意**不设** core/max 池大小:虚拟线程模式下"每个任务一条虚拟线程",池大小不再决定并发度,
     * 设了反而会让人误以为它是个有界的线程池(真正的边界在数据库连接池那一侧,见 1.2)。
     */
    @Bean("applicationTaskExecutor")
    public TaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("async-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setVirtualThreads(true);
        executor.initialize();
        return executor;
    }
}
