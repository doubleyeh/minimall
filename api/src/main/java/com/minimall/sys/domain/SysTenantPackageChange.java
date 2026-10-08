package com.minimall.sys.domain;

import com.minimall.infra.persistence.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 套餐变更记录(架构文档 4.8、4.8.1)。
 *
 * <p>平台级审计数据:有 {@code tenant_id} 但**不参与租户过滤**——它是超管排查用的流水,
 * 按租户过滤反而看不到全貌(见 4.6.1)。所以这里的 tenantId 是普通字段,不是 BaseTenantEntity 的租户列。
 *
 * <p><b>记录只追加,当前套餐由记录推出</b>:已生效({@code effectiveDate <= 当天})的记录里
 * {@code id} 最大的那条就是当前套餐。所以:
 * <ul>
 *   <li>选当天生效 = 立即生效({@code effectiveDate} 就是当天)</li>
 *   <li>同一生效日、后写的记录胜出 —— 这是"发现写错再补一条"的纠错手段</li>
 *   <li>立即变更也会写记录,因此会压住更早的预约(预定被后写的即时变更取代)</li>
 * </ul>
 * {@code Tenant.packageId} 是"当前生效套餐"的冗余列,由立即变更与到点任务共同维护。
 */
@Entity
@Table(name = "sys_tenant_package_change")
@Getter
@Setter
public class SysTenantPackageChange extends BaseAuditEntity {

    /** 触发源:1-租户换套餐(4.8) 2-平台修改套餐菜单(4.8.1) 3-预约到点自动生效 */
    public static final int TRIGGER_TENANT_SWITCH = 1;
    public static final int TRIGGER_PACKAGE_MENU_EDIT = 2;
    public static final int TRIGGER_SCHEDULED_APPLY = 3;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "trigger_type", nullable = false)
    private Integer triggerType;

    /** NULL 表示"变更前不限";trigger_type = 2/3 时与 newPackageId 相同 */
    @Column(name = "old_package_id")
    private Long oldPackageId;

    @Column(name = "new_package_id", nullable = false)
    private Long newPackageId;

    /** 生效日(只到日)。{@code <= 当天} 即已生效。 */
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** 生效时该套餐的菜单 ID 快照(JSON 数组):事后回溯"当时这个租户能用什么"。 */
    @Column(name = "package_menu_ids", columnDefinition = "text")
    private String packageMenuIds;

    /** 本次新增授权的菜单 ID(仅默认管理员角色),JSON 数组 */
    @Column(name = "added_menu_ids", columnDefinition = "text")
    private String addedMenuIds;

    /** 本次收回的菜单 ID(该租户全部角色一并生效),JSON 数组 */
    @Column(name = "revoked_menu_ids", columnDefinition = "text")
    private String revokedMenuIds;
}
