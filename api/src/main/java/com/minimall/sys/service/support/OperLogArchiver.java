package com.minimall.sys.service.support;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.audit.AuditProperties;
import com.minimall.infra.export.CsvExporter;
import com.minimall.infra.file.FileProperties;
import com.minimall.infra.tenant.TenantFilterService;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.api.dto.OperLogView;
import com.minimall.sys.domain.QSysOperLog;
import com.minimall.sys.domain.repository.SysOperLogRepository;
import com.minimall.sys.service.OperLogService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 审计日志归档(架构文档 7.2)。
 *
 * <p><b>解决什么</b>:操作日志是"每个请求多一行"的表,只增不减。注销租户时日志被特意保留下来(见
 * 4.11),于是它更不可能靠"删租户"顺带收敛。库里留着的量必须有上限。
 *
 * <p><b>顺序是"先落盘再删库",不是反过来</b>:反过来的话,进程在两者之间挂掉就真的丢数据了;
 * 现在最坏结果是某批日志在归档文件里重复出现一次(文件按运行时间命名,人能看到)。重复比丢失容易接受得多。
 *
 * <p><b>写不成就一行都不删</b>:文件写失败时抛错并中止本轮,库里那些行原样留着 —— 下一天再试。
 *
 * <p>每批一个事务(用 {@link TransactionTemplate} 而不是把整个方法包在一个事务里):日志是异步写入的
 * 热表,一个跨几万行删除的长事务会把异步写入堵住,而日志写不进去是不能接受的。
 */
@Component
public class OperLogArchiver {

    private static final Logger log = LoggerFactory.getLogger(OperLogArchiver.class);
    /** 每批读多少行:批太大则单次事务删得久,批太小则来回次数多。 */
    private static final int BATCH_SIZE = 2000;
    /** 单轮上限:任务不该跑太久,积压多就多跑几天(每次都会记日志说明还剩多少)。 */
    private static final int MAX_ROWS_PER_RUN = 20_000;
    private static final String ARCHIVE_DIR = "audit-archive";

    private final OperLogService operLogService;
    private final SysOperLogRepository operLogRepository;
    private final AuditProperties auditProperties;
    private final FileProperties fileProperties;
    private final TenantFilterService tenantFilterService;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;

    public OperLogArchiver(OperLogService operLogService,
                           SysOperLogRepository operLogRepository,
                           AuditProperties auditProperties,
                           FileProperties fileProperties,
                           TenantFilterService tenantFilterService,
                           TransactionTemplate transactionTemplate,
                           EntityManager entityManager) {
        this.operLogService = operLogService;
        this.operLogRepository = operLogRepository;
        this.auditProperties = auditProperties;
        this.fileProperties = fileProperties;
        this.tenantFilterService = tenantFilterService;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
    }

    /**
     * 归档并删除超过保留期的日志。
     *
     * @return 实际归档并删除的行数;策略关闭或没有到期数据时返回 0
     */
    public int archive() {
        int keepDays = auditProperties.archiveAfterDays();
        if (keepDays <= 0) {
            log.debug("未启用日志归档(minimall.audit.archive-after-days=0)");
            return 0;
        }
        // 归档是平台级操作(跨全部租户),用超管上下文表达;过滤器在事务边界才启用,这里手动补一次
        return TenantContext.callAsTenant(null, true, () -> {
            tenantFilterService.apply(entityManager);
            return archiveExpired(LocalDateTime.now().minusDays(keepDays));
        });
    }

    private int archiveExpired(LocalDateTime deadline) {
        long pending = countOlderThan(deadline);
        if (pending == 0) {
            return 0;
        }
        Path archiveFile = archiveFilePath();
        int archived = 0;
        try {
            Files.createDirectories(archiveFile.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(archiveFile, StandardCharsets.UTF_8)) {
                writer.write(CsvExporter.header(OperLogCsv.HEADERS));
                archived = writeAndDeleteInBatches(writer, deadline);
            }
        } catch (IOException ex) {
            // 归档写不成就不删:库里那些行下一天再试,不能为了"清理干净"把审计记录丢掉
            log.error("日志归档写文件失败,本轮不删除任何记录 file={}", archiveFile, ex);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "日志归档失败,本轮未删除记录");
        }
        if (archived == 0) {
            // 一轮下来一行没归档(比如都被别的实例处理了),不留空文件
            deleteQuietly(archiveFile);
            return 0;
        }
        log.warn("日志归档完成 file={} 归档并删除 {} 行(保留期 {})",
                archiveFile, archived, auditProperties.archiveAfterDays());
        if (pending > archived) {
            log.warn("仍有 {} 行到期日志未归档,超过单轮上限 {} 行,下一天继续",
                    pending - archived, MAX_ROWS_PER_RUN);
        }
        return archived;
    }

    private int writeAndDeleteInBatches(BufferedWriter writer, LocalDateTime deadline) throws IOException {
        int archived = 0;
        while (archived < MAX_ROWS_PER_RUN) {
            List<OperLogView> batch = operLogService.listOlderThan(deadline, BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            // 整批拼好再一次写出:避免"写了一半"的文件里出现被截断的半行
            StringBuilder text = new StringBuilder();
            for (OperLogView view : batch) {
                text.append(OperLogCsv.line(view));
            }
            writer.write(text.toString());
            // 先落盘再删库(见类注释):这里必须 flush,不能等 close
            writer.flush();

            Long maxId = batch.get(batch.size() - 1).id();
            long deleted = deleteUpTo(deadline, maxId);
            if (deleted < batch.size()) {
                // 期间有人删了行(正常不该发生):数目对不上就停手,宁可少删也不要删到没归档的
                log.warn("本批删除行数({})少于归档行数({}),已停止本轮", deleted, batch.size());
                break;
            }
            archived += (int) deleted;
        }
        return archived;
    }

    private long countOlderThan(LocalDateTime deadline) {
        return transactionTemplate.execute(status ->
                operLogRepository.count(QSysOperLog.sysOperLog.createTime.before(deadline)));
    }

    private long deleteUpTo(LocalDateTime deadline, Long maxId) {
        Integer deleted = transactionTemplate.execute(status ->
                operLogRepository.deleteArchivedUpTo(deadline, maxId));
        return deleted == null ? 0 : deleted;
    }

    /** 归档文件的落点:与上传文件同一个根目录(它已经被要求挂持久卷),按运行时间命名。 */
    private Path archiveFilePath() {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return Paths.get(fileProperties.rootDir(), ARCHIVE_DIR, "oper-log-" + stamp + ".csv");
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            log.warn("清理空归档文件失败 file={}", file, ex);
        }
    }
}
