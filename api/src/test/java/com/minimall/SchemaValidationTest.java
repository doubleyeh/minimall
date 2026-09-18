package com.minimall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

/**
 * 实体映射与建表脚本一致性校验(架构文档 9.3:三环境统一 {@code ddl-auto = validate})。
 *
 * <p>为什么值得单独一条用例:实体与 SQL 是两份人工维护的产物,列名写错、类型不匹配、漏映射一个 NOT NULL 列
 * 这类问题,如果不在这里暴露,就会在"部署到 test/prod"时才炸。本用例把这份风险压到开发阶段。
 *
 * <p>它必须连真实 MySQL(用 {@code @AutoConfigureTestDatabase(replace = NONE)} 关掉内存库替换),
 * 所以打了 {@code integration} 标签:默认 {@code mvn test} 不跑,CI 里用
 * {@code mvn test -Dexcluded.groups=} 全量跑(见 9.4)。
 *
 * <p>跑之前测试库的 schema 必须已经由 Flyway 建好(和 dev/prod 同一套脚本,见 9.4)。
 */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("dev")
class SchemaValidationTest {

    @Test
    @DisplayName("实体映射与 V1 建表脚本一致:Hibernate validate 能通过")
    void entityMappingMatchesSchema() {
        // 这个用例没有断言:Spring 上下文能起来就说明 schema 校验通过——
        // 列名、类型、缺失列的任何不一致都会在启动时抛 SchemaManagementException,
        // 并且消息里会直接给出表名与列名,不需要再自己找。
    }
}
