package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 秒杀活动(商城设计文档 3.12)。
 *
 * <p><b>状态只能前进,且每次迁移都用条件 UPDATE</b>({@code where status = 期望值}):
 * 多实例 + 手动触发 + 任务重跑都会重复执行同一段逻辑,受影响行数为 0 就说明别人已经做过了。
 *
 * <p>活动时间窗只是展示用的边界,真正的下单校验在事务内按 {@code status = 进行中} 与当前时间二次判断。
 */
@Entity
@Table(name = "mall_seckill_activity")
@Getter
@Setter
public class MallSeckillActivity extends BaseTenantEntity {

    /** 草稿:可编辑、可删除,不能下单。 */
    public static final int STATUS_DRAFT = 0;
    /** 待开始:库存已冻结,未到开抢时间。 */
    public static final int STATUS_PENDING = 1;
    /** 进行中:可下单。 */
    public static final int STATUS_RUNNING = 2;
    /** 已结束:剩余配额已解冻。 */
    public static final int STATUS_ENDED = 3;
    /** 已取消:未开始或进行中被商家取消,剩余配额已解冻。 */
    public static final int STATUS_CANCELLED = 4;

    @Column(name = "activity_name", nullable = false, length = 64)
    private String activityName;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    /** 0-草稿 1-待开始 2-进行中 3-已结束 4-已取消。 */
    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** 活动是否覆盖此刻。 */
    public boolean covers(LocalDateTime moment) {
        return !moment.isBefore(startTime) && moment.isBefore(endTime);
    }
}
