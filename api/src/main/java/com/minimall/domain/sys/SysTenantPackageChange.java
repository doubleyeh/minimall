package com.minimall.domain.sys;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 套餐变更记录(架构文档 4.8、4.8.1)。
 *
 * <p>平台级审计数据:有 {@code tenant_id} 但**不参与租户过滤**——它是超管排查用的流水,
 * 按租户过滤反而看不到全貌(见 4.6.1)。所以这里的 tenantId 是普通字段,不是 BaseTenantEntity 的租户列。
 */
@Entity
@Table(name = "sys_tenant_package_change")
@Getter
@Setter
public class SysTenantPackageChange extends BaseAuditEntity {

    /** 触发源:1-租户换套餐(4.8) 2-平台修改套餐菜单(4.8.1) */
    public static final int TRIGGER_TENANT_SWITCH = 1;
    public static final int TRIGGER_PACKAGE_MENU_EDIT = 2;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "trigger_type", nullable = false)
    private Integer triggerType;

    /** NULL 表示"变更前不限";trigger_type = 2 时与 newPackageId 相同 */
    @Column(name = "old_package_id")
    private Long oldPackageId;

    @Column(name = "new_package_id", nullable = false)
    private Long newPackageId;

    /** 本次新增授权的菜单 ID(仅默认管理员角色),JSON 数组 */
    @Column(name = "added_menu_ids", columnDefinition = "text")
    private String addedMenuIds;

    /** 本次收回的菜单 ID(该租户全部角色一并生效),JSON 数组 */
    @Column(name = "revoked_menu_ids", columnDefinition = "text")
    private String revokedMenuIds;
}
