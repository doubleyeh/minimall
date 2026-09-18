package com.minimall.support;

import com.minimall.infra.id.SnowflakeIdGenerator;
import com.minimall.infra.id.SnowflakeProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 数据库切片测试(@DataJpaTest)需要的非 JPA 基础设施。
 *
 * <p>为什么需要它:切片测试只加载 JPA 相关组件,不会加载 {@code @Component}。
 * 而实体基类的 {@code @PrePersist} 要生成雪花 ID,靠的是 {@link SnowflakeIdGenerator} 的静态入口
 * (它在构造时登记静态引用,因为 JPA 回调不是 Spring Bean)。不给切片提供这个 Bean,
 * 一持久化就会抛"雪花ID生成器尚未初始化"——这个报错本身就是设计意图的一部分:
 * 宁可失败,也不要静默生成 ID 为 null 或全 0 的数据。
 *
 * <p>所有需要写库的测试都应该 {@code @Import(JpaTestSupport.class)},不要各写一份。
 */
@TestConfiguration
public class JpaTestSupport {

    @Bean
    public SnowflakeProperties snowflakeProperties() {
        return new SnowflakeProperties(1, 1);
    }

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(SnowflakeProperties properties) {
        return new SnowflakeIdGenerator(properties);
    }
}
