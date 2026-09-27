package com.minimall.sys.service;

import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.id.SnowflakeIdGenerator;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.domain.SysOperLog;
import com.minimall.sys.domain.repository.SysOperLogRepository;
import com.minimall.sys.service.support.OperLogArchiver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计日志归档(架构文档 7.2)。
 *
 * <p>这是一个"会删数据"的任务,所以用例的重点是两件必须同时成立的事:
 * <b>库里的行确实少了</b>,以及<b>归档文件里确实有这些行</b>。只验前者等于把日志丢了还不知道。
 *
 * <p>保留期用默认的 180 天,用例把日志时间往前调,不依赖配置覆盖(测试 profile 里该任务被关掉了,
 * 这里直接调归档组件)。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class OperLogArchiveIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long PLATFORM_ADMIN_USER_ID = 1L;
    /** 与 application-test.yml 的 minimall.file.root-dir 一致。 */
    private static final Path ARCHIVE_DIR = Path.of("target", "test-files", "audit-archive");

    @Autowired
    private OperLogArchiver operLogArchiver;
    @Autowired
    private SysOperLogRepository operLogRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearContexts() {
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("超期日志:归档成文件、再从库里删除;没超期的原样留着")
    void archivesExpiredLogsAndKeepsRecentOnes() throws Exception {
        String expiredModule = "归档用例-超期" + suffix();
        String recentModule = "归档用例-未超期" + suffix();
        insertLog(expiredModule, LocalDateTime.now().minusDays(200));
        insertLog(expiredModule, LocalDateTime.now().minusDays(199));
        insertLog(recentModule, LocalDateTime.now().minusDays(1));

        int archived = operLogArchiver.archive();

        assertThat(archived).as("两条超期日志应当被归档并删除").isEqualTo(2);
        assertThat(countOf(expiredModule)).as("删了就是删了").isZero();
        assertThat(countOf(recentModule)).as("没超期的一行都不能动").isEqualTo(1);

        String archiveContent = latestArchiveContent();
        assertThat(archiveContent).as("表头要有").contains("时间,租户ID,用户ID,模块");
        assertThat(archiveContent).as("归的正是那些行").contains(expiredModule);
        assertThat(archiveContent).as("没超期的不该出现在归档里").doesNotContain(recentModule);
        assertThat(archiveContent.lines().count()).as("表头 + 2 条数据").isEqualTo(3);
    }

    @Test
    @DisplayName("没有到期数据:什么都不做,也不留空文件")
    void doesNothingWhenNothingExpired() throws Exception {
        String recentModule = "归档用例-新" + suffix();
        insertLog(recentModule, LocalDateTime.now());
        long filesBefore = archiveFileCount();

        int archived = operLogArchiver.archive();

        assertThat(archived).isZero();
        assertThat(countOf(recentModule)).as("不该误删").isEqualTo(1);
        assertThat(archiveFileCount()).as("没东西可归档就不该产生空文件").isEqualTo(filesBefore);
    }

    // ——— 辅助 ———

    private void insertLog(String module, LocalDateTime createTime) {
        asSuperUser(() -> {
            SysOperLog log = new SysOperLog();
            log.setId(SnowflakeIdGenerator.nextId());
            log.setTenantId(PLATFORM_TENANT_ID);
            log.setUserId(PLATFORM_ADMIN_USER_ID);
            log.setModule(module);
            log.setMethod("GET /用例");
            log.setStatus(1);
            log.setIp("127.0.0.1");
            operLogRepository.save(log);
            return null;
        });
        // create_time 是 @CreatedDate(updatable = false),要造历史数据只能绕过 JPA 改库
        jdbcTemplate.update("update sys_oper_log set create_time = ? where module = ?", createTime, module);
    }

    private long countOf(String module) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from sys_oper_log where module = ?", Long.class, module);
        return count == null ? 0 : count;
    }

    private long archiveFileCount() throws IOException {
        if (!Files.isDirectory(ARCHIVE_DIR)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(ARCHIVE_DIR)) {
            return files.count();
        }
    }

    private String latestArchiveContent() throws IOException {
        try (Stream<Path> files = Files.list(ARCHIVE_DIR)) {
            List<Path> sorted = files.sorted().toList();
            assertThat(sorted).as("应当产生了归档文件:%s", ARCHIVE_DIR.toAbsolutePath()).isNotEmpty();
            return Files.readString(sorted.get(sorted.size() - 1), StandardCharsets.UTF_8);
        }
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, PLATFORM_ADMIN_USER_ID, "127.0.0.1", "archive-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }
}
