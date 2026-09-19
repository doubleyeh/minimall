package com.minimall.sys.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import com.minimall.infra.persistence.DataScopeConditions;
import com.minimall.infra.persistence.OwnedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 用户(架构文档 4.6.1):租户级表,同时参与**数据权限**过滤。
 *
 * <p>三个标记的含义:
 * <ul>
 *   <li>继承 {@code BaseTenantEntity}:自动带租户过滤 + tenant_id 回填(见 4.2/4.5)</li>
 *   <li>{@code @Filter(dataScopeFilter)}:这一张表参与"按部门范围看数据"(5.3)。
 *       条件里的 {@code dept_id}/{@code create_by} 本表都有,满足使用前提</li>
 *   <li>实现 {@code OwnedEntity}:{@code create_by} 语义是归属人,写入必须非空(5.3 的硬约束),
 *       由 {@code TenantOwnershipListener} 在持久化前校验</li>
 * </ul>
 *
 * <p>{@code isSuper} 是**用户级**的跨租户开关(4.10):只有种子数据/运维脚本能设置,
 * 接口既不接受也不暴露它。{@code mustChangePassword} 对应 7.1.2 的强制改密拦截。
 */
@Entity
@Table(name = "sys_user")
@Getter
@Setter
@Filter(name = "dataScopeFilter", condition = DataScopeConditions.SQL_SELF_VISIBLE)
public class SysUser extends BaseTenantEntity implements OwnedEntity {

    @Column(name = "dept_id")
    private Long deptId;

    @Column(name = "username", nullable = false, length = 64)
    private String username;

    /** BCrypt hash。任何接口与日志都不得输出该字段(见 7.1.2) */
    @Column(name = "password", nullable = false, length = 128)
    private String password;

    @Column(name = "nickname", length = 64)
    private String nickname;

    @Column(name = "phone", length = 20)
    private String phone;

    /** 0-禁用 1-正常。禁用后在线会话按 4.11 在下一个请求失效 */
    @Column(name = "status", nullable = false)
    private Integer status;

    /** 1-平台超管:跳过租户过滤、权限短路为全量(4.10)。只能由种子数据/运维脚本设置 */
    @Column(name = "is_super", nullable = false)
    private Integer isSuper;

    /** 1-强制改密:除改密/登出/刷新权限外一律 403(7.1.2) */
    @Column(name = "must_change_password", nullable = false)
    private Integer mustChangePassword;

    @Column(name = "pwd_update_time")
    private LocalDateTime pwdUpdateTime;

    @Column(name = "login_fail_count", nullable = false)
    private Integer loginFailCount;

    /** 登录失败锁定截止时间(7.1.1) */
    @Column(name = "lock_time")
    private LocalDateTime lockTime;

    /** 该用户持有的角色。关联表没有 tenant_id,安全性靠"只能通过本实体访问"(见 4.6.1) */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "sys_user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role_id", nullable = false)
    private Set<Long> roleIds = new HashSet<>();

    public boolean isEnabled() {
        return status != null && status == 1;
    }

    public boolean isSuperUser() {
        return isSuper != null && isSuper == 1;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword != null && mustChangePassword == 1;
    }
}
