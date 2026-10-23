package com.minimall.mall.service;

import com.minimall.infra.schedule.ManualTaskRegistry;
import com.minimall.infra.schedule.ScheduledTaskLock;
import com.minimall.sys.service.support.TenantTaskRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 秒杀活动状态推进(商城设计文档 3.12):每分钟把"到点开始/到点结束"的活动推进一步。
 *
 * <p>推进本身是幂等的(条件更新),所以手动重跑、多实例同时跑都不会重复冻结或解冻。
 * 漏跑也没关系:结束这一批同时收待开始与进行中的活动,服务停过一段时间也能自己收敛。
 */
@Component
public class SeckillScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(SeckillScheduledTasks.class);

    /** 系统操作人:平台超管,仅用于审计字段。 */
    private static final long SYSTEM_ACTOR_ID = 1L;

    private static final String TASK_NAME = "秒杀活动状态推进";

    private final TenantTaskRunner tenantTaskRunner;
    private final SeckillService seckillService;
    private final ScheduledTaskLock taskLock;

    public SeckillScheduledTasks(TenantTaskRunner tenantTaskRunner,
                                 SeckillService seckillService,
                                 ScheduledTaskLock taskLock,
                                 ManualTaskRegistry manualTaskRegistry) {
        this.tenantTaskRunner = tenantTaskRunner;
        this.seckillService = seckillService;
        this.taskLock = taskLock;
        manualTaskRegistry.register(TASK_NAME, this::advance);
    }

    @Scheduled(cron = "${minimall.schedule.cron.seckill-advance:0 * * * * ?}")
    public void advance() {
        taskLock.runIfNotLocked(TASK_NAME, () -> {
            var result = tenantTaskRunner.runForEachTenant(TASK_NAME, SYSTEM_ACTOR_ID, tenantId -> {
                int started = seckillService.startDue();
                int ended = seckillService.endDue();
                if (started + ended > 0) {
                    log.info("租户 {} 秒杀推进:开始 {} 场 / 结束 {} 场", tenantId, started, ended);
                }
            });
            if (result.failedTenantIds() != null && !result.failedTenantIds().isEmpty()) {
                throw new IllegalStateException("任务「" + TASK_NAME + "」部分租户执行失败:"
                        + result.failedTenantIds());
            }
        });
    }
}
