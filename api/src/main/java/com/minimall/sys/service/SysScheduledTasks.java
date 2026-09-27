package com.minimall.sys.service;

import com.minimall.infra.schedule.ScheduledTaskLock;
import com.minimall.infra.tenant.TenantSnapshot;
import com.minimall.sys.api.dto.DictItemView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 平台级定时任务(架构文档 6.2)。
 *
 * <p>这是第一个**跨租户**的定时任务:商城侧的四个任务都要按租户逐个跑({@code TenantTaskRunner}),
 * 而租户自身的到期处理本来就该横着看一遍所有租户,不能套那个模式。
 */
@Component
public class SysScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(SysScheduledTasks.class);

    /** 到期提醒的默认提前天数,可被字典 {@code tenant_expire_notice_days} 覆盖。 */
    private static final int DEFAULT_EXPIRE_NOTICE_DAYS = 7;
    private static final String DICT_EXPIRE_NOTICE_DAYS = "tenant_expire_notice_days";

    private final TenantService tenantService;
    private final DictService dictService;
    private final ScheduledTaskLock taskLock;

    public SysScheduledTasks(TenantService tenantService, DictService dictService, ScheduledTaskLock taskLock) {
        this.tenantService = tenantService;
        this.dictService = dictService;
        this.taskLock = taskLock;
    }

    /**
     * 租户到期处理(每天 3:50,与商城侧 3:30 的优惠券清理错开):
     * 先把快到期的记一条告警,再把已到期的置为禁用。
     *
     * <p>为什么要主动禁用:有效期一直只是"用户请求时被动校验",库里状态永远停在"正常",
     * 看租户列表会以为一切正常。
     */
    @Scheduled(cron = "0 50 3 * * ?")
    public void handleTenantExpiry() {
        // 多实例部署时每个实例都会触发,必须抢锁:重复执行会把同一批租户禁两遍(幂等但不该白跑)
        taskLock.runIfNotLocked("租户到期处理", () -> {
            LocalDateTime now = LocalDateTime.now();
            int noticeDays = noticeDays();

            List<TenantSnapshot> expiring = tenantService.listExpiringBetween(now, now.plusDays(noticeDays));
            if (!expiring.isEmpty()) {
                log.warn("有 {} 个租户将在 {} 天内到期,请及时续期:{}", expiring.size(), noticeDays,
                        expiring.stream().map(t -> t.tenantCode() + "(" + t.expireTime() + ")").toList());
            }

            int disabled = tenantService.disableExpiredTenants();
            if (disabled > 0) {
                log.info("租户到期处理:已禁用 {} 个到期租户", disabled);
            }
        });
    }

    /** 提醒阈值取字典;取不到或不是整数就用默认值 —— 配置问题不该让任务整体卡住。 */
    private int noticeDays() {
        try {
            List<DictItemView> items = dictService.items(DICT_EXPIRE_NOTICE_DAYS);
            return items.isEmpty()
                    ? DEFAULT_EXPIRE_NOTICE_DAYS
                    : Integer.parseInt(items.get(0).value().trim());
        } catch (NumberFormatException ex) {
            log.warn("字典 {} 的值不是整数,已使用默认值 {}", DICT_EXPIRE_NOTICE_DAYS, DEFAULT_EXPIRE_NOTICE_DAYS);
            return DEFAULT_EXPIRE_NOTICE_DAYS;
        }
    }
}
