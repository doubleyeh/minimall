package com.minimall.sys.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.HashSet;
import java.util.Set;

/**
 * 角色(架构文档 4.6、4.7、5.3)。
 *
 * <p>{@code tenantId} 始终非空——不再有"可空表示平台模板"的例外:新租户的初始角色由套餐生成,
 * 平台租户自己的角色挂在平台租户下(见 4.7 与 4.10)。
 *
 * <p>不参与数据权限过滤(4.6.1):"只看自己创建的角色"没有业务意义,
 * 角色列表的可见性由菜单权限决定。
 */
@Entity
@Table(name = "sys_role")
@Getter
@Setter
public class SysRole extends BaseTenantEntity {

    @Column(name = "role_key", nullable = false, length = 64)
    private String roleKey;

    @Column(name = "role_name", nullable = false, length = 64)
    private String roleName;

    /** 1-仅本人 2-本部门 3-本部门及以下 4-自定义部门 5-全部。多角色时取最大值,见 5.3 */
    @Column(name = "data_scope", nullable = false)
    private Integer dataScope;

    /**
     * 1-租户创建时自动生成的默认管理员角色(4.8)。
     *
     * <p>它是套餐的投影:套餐变更时**只自动同步这个角色**,且它的菜单**不接受人工增删**、
     * 角色本身**不允许删除**(5.2.1、5.6)。字段名是机器标识,不受业务改名影响。
     */
    @Column(name = "is_default", nullable = false)
    private Integer isDefault;

    @Column(name = "status", nullable = false)
    private Integer status;

    /** 角色被授权的菜单(即权限的事实来源,套餐只是它的合法边界,见 5.2.1) */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "sys_role_menu", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "menu_id", nullable = false)
    private Set<Long> menuIds = new HashSet<>();

    /** data_scope = 4(自定义部门)时使用的部门集合 */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "sys_role_dept", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "dept_id", nullable = false)
    private Set<Long> deptIds = new HashSet<>();

    public boolean isDefaultRole() {
        return isDefault != null && isDefault == 1;
    }

    public boolean isEnabled() {
        return status != null && status == 1;
    }

    public boolean isCustomDeptScope() {
        // 4 = 自定义部门(建表脚本 sys_role.data_scope 的注释)
        return dataScope != null && dataScope == 4;
    }
}
