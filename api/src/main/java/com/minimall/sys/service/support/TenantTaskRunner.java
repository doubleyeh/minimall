package com.minimall.sys.service.support;

import com.minimall.sys.domain.Tenant;
import com.minimall.sys.domain.repository.TenantRepository;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "逐租户执行"的任务骨架(架构文档 6.2)。
 *
 * <p><b>它解决的是什么问题</b>:定时任务 / 运维脚本这类入口没有登录态,进入方法体时
 * {@code TenantContext} 是空的。若图省事用超管身份一把跑完全部租户,等于给这些入口开了
 * "能看到所有租户数据"的后门,而且单租户失败会把整批带崩。正确做法是**逐个租户进上下文**,
 * 每个租户各自一个事务、各自 try/catch。本类把这段容易写错的循环固定下来,业务侧只写"单个租户要做什么"。
 *
 * <p><b>三条使用约束</b>:
 * <ol>
 *   <li>**必须在事务外调用**。每个租户要独立提交,若被包在调用方的事务里,一个租户失败会把整批回滚,
 *       与"单租户失败不影响其他租户"直接冲突。这里会主动检查并抛错,不靠约定</li>
 *   <li>传进来的消费者**必须是事务方法**(通常是某个 service 的 {@code @Transactional} 方法)。
 *       没有事务就没有 Session 边界,{@code TenantFilterAspect} 没地方 enable 过滤器,隔离会静默失效(6.3)</li>
 *   <li>不要用超管身份表达"我都要处理":当前 {@code runAsTenant(..., false, ...)} 是唯一入口,
 *       需要跨租户的能力时应该显式加方法,而不是靠这里的参数</li>
 * </ol>
 *
 * <p>多实例部署时 {@code @Scheduled} 会在每个实例上触发、同一批租户被处理多遍,
 * 需要分布式锁或调度中心,本方案未展开(见架构文档 6.2 与第 10 节)。
 */
@Component
public class TenantTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantTaskRunner.class);

    /** 系统任务没有登录用户,审计快照里的 IP 用这个占位值,便于在日志里区分"人做的"与"系统做的"。 */
    private static final String SYSTEM_IP = "system";

    private final TenantRepository tenantRepository;

    public TenantTaskRunner(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    /**
     * 逐租户执行(只读任务用)。
     *
     * <p>只读任务不需要 {@code create_by},所以不绑定审计快照——但**要写入的任务必须用下面那个重载**,
     * 否则归属人缺失会被 {@code TenantOwnershipListener} 拦下(5.3 的硬约束)。
     */
    public RunResult runForEachTenant(String taskName, Consumer<Long> perTenantTask) {
        return runForEachTenant(taskName, null, perTenantTask);
    }

    /**
     * 逐租户执行,并为每个租户绑定一份审计快照({@code tenantId} 用当前租户、{@code userId} 用系统操作人)。
     *
     * @param actorUserId 系统任务的操作人 ID。会写进 {@code create_by/update_by},
     *                    所以**不能传 null**(OwnedEntity 写入时归属人必须非空)
     */
    public RunResult runForEachTenant(String taskName, Long actorUserId, Consumer<Long> perTenantTask) {
        requireNoAmbientTransaction(taskName);

        List<Tenant> tenants = tenantRepository.findAll();
        List<Long> failed = new ArrayList<>();
        int succeeded = 0;

        for (Tenant tenant : tenants) {
            if (!usable(tenant)) {
                // 禁用/已过期的租户不处理:与 4.11 的口径一致(它们连登录都进不来)
                log.debug("跳过不可用租户 tenantId={} status={} expireTime={}",
                        tenant.getId(), tenant.getStatus(), tenant.getExpireTime());
                continue;
            }
            try {
                TenantContext.runAsTenant(tenant.getId(), false, () -> {
                    if (actorUserId != null) {
                        // 快照显式构造:异步/无请求场景下这两个字段没有别的来源(4.12)
                        AuditContext.bind(new AuditContext(tenant.getId(), actorUserId, SYSTEM_IP, null));
                    }
                    try {
                        perTenantTask.accept(tenant.getId());
                    } finally {
                        AuditContext.clear();
                    }
                });
                succeeded++;
            } catch (Exception ex) {
                // 单个租户失败只记录、不中断:其他租户照常处理,失败的那个停在未处理状态、可重跑(6.2)
                failed.add(tenant.getId());
                log.error("逐租户任务失败 task={} tenantId={} —— 该租户未处理完成,修复后可重跑",
                        taskName, tenant.getId(), ex);
            }
        }

        log.info("逐租户任务结束 task={} 成功={} 失败={} 失败租户={}", taskName, succeeded, failed.size(), failed);
        return new RunResult(taskName, succeeded, List.copyOf(failed));
    }

    /**
     * 必须不在事务里:否则所有租户共用一个事务,一个失败就全批回滚(与 6.2 的语义冲突)。
     * 这里主动失败而不是"尽力而为"——等到线上出现"个别租户失败导致整批回滚"时再排查成本太高。
     */
    private void requireNoAmbientTransaction(String taskName) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("逐租户任务必须在事务外调用:" + taskName
                    + " 需要每个租户各自独立提交事务(架构文档 6.2)");
        }
    }

    private boolean usable(Tenant tenant) {
        if (tenant.getStatus() == null || tenant.getStatus() != 1) {
            return false;
        }
        return tenant.getExpireTime() == null || tenant.getExpireTime().isAfter(LocalDateTime.now());
    }

    /**
     * @param taskName        任务名,只用于日志与返回值,便于按租户重试时定位
     * @param succeeded       成功处理的租户数
     * @param failedTenantIds 失败的租户 ID(非空即表示有租户停在未处理状态,需要重跑)
     */
    public record RunResult(String taskName, int succeeded, List<Long> failedTenantIds) {

        public boolean allSucceeded() {
            return failedTenantIds.isEmpty();
        }
    }
}
