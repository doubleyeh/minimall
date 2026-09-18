package com.minimall.infra.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 事务切面顺序(架构文档 4.2)。
 *
 * <p><b>为什么必须显式指定 order</b>:Spring 的 {@code @Transactional} advisor 默认 order 是
 * {@code Ordered.LOWEST_PRECEDENCE}(最低优先级 = 最内层)。租户过滤切面 {@code TenantFilterAspect}
 * 的 order 是 10,如果不把事务 advisor 的 order 明确压到更小的值(更高优先级 = 更外层),
 * 两者的相对顺序就是**未定义的**:
 * <ul>
 *   <li>租户切面跑到事务**外面**时,{@code EntityManager.unwrap(Session.class)} 拿到的不是绑定事务的 Session,
 *       enable 的结果会被随后真正创建的 Session 覆盖掉,过滤静默失效——现象是"偶发看到其他租户的数据"</li>
 *   <li>而且这种问题在同步请求里常常"看起来正常"(HTTP 入口已经 enable 过一次),
 *       只在异步路径暴露,极难排查</li>
 * </ul>
 *
 * <p>所以在事务切面 order = 0、租户切面 order = 10 的组合下,顺序固定为:
 * 事务开始 → 租户/数据权限过滤 enable → 业务方法 → 提交。
 *
 * <p>声明了本配置后,Spring Boot 的 {@code TransactionAutoConfiguration} 会退让
 * (它对 {@code AbstractTransactionManagementConfiguration} 有 {@code @ConditionalOnMissingBean}),
 * 不会出现两个事务 advisor。
 */
@Configuration
@EnableTransactionManagement(order = 0)
public class TransactionConfig {
}
