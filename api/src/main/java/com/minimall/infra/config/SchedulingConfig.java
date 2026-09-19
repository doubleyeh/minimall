package com.minimall.infra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启定时任务(架构文档 6.2)。
 *
 * <p><b>为什么需要这个类</b>:{@code @Scheduled} 只是"给方法打了个标记",
 * 真正让它被调度的是 {@code @EnableScheduling}。只写 {@code @Scheduled} 而不开启调度,
 * 定时任务**一次都不会跑,而且不报任何错** —— 表现为"超时订单永远不会被关闭",
 * 而排查时看代码完全正常,是很容易耗掉半天的坑。所以这里显式开启,并把它放在配置类里
 * (与启动类分开:调度是基础设施配置,不是应用入口的职责)。
 *
 * <p>调度线程池使用 Spring 默认的单线程执行器。当前任务总量很小(每分钟 3 个),
 * 且每个任务内部是"逐租户串行",单线程足够;若将来任务变多或单租户处理变慢,
 * 应通过 {@code spring.task.scheduling.pool.size} 或自定义 {@code TaskScheduler} 扩容
 * (注意 6.3 的上下文传递要求:调度线程里必须由 {@code TenantTaskRunner} 显式设置租户上下文)。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
