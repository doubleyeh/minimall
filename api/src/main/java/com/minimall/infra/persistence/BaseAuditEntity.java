package com.minimall.infra.persistence;

import com.minimall.infra.id.SnowflakeIdGenerator;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 平台级实体基类(架构文档 4.6):只有审计字段,不带租户过滤。
 *
 * <p>适用:{@code tenant}、{@code sys_menu}、{@code sys_package}、{@code sys_package_menu}、
 * {@code sys_tenant_package_change}、{@code sys_dict_type}、{@code sys_dict_data}、{@code sys_pay_account}。
 *
 * <p>审计字段的填充(4.4):时间字段用 Spring Data JPA Auditing 注解读值,
 * 操作人字段由 {@code AuditorAware} 从 {@link com.minimall.infra.audit.AuditContext} 取(不是直接读 Sa-Token 会话,
 * 否则异步落库时取不到,见 4.12)。
 *
 * <p>主键(4.3):在 {@code @PrePersist} 里"为空则生成",不用数据库自增,也不用 Hibernate 的
 * {@code IdentifierGenerator} SPI(理由见文档 4.3)。
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseAuditEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @CreatedDate
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @LastModifiedDate
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;

    @CreatedBy
    @Column(name = "create_by", updatable = false)
    private Long createBy;

    @LastModifiedBy
    @Column(name = "update_by")
    private Long updateBy;

    @PrePersist
    void assignIdIfAbsent() {
        if (id == null) {
            id = SnowflakeIdGenerator.nextId();
        }
    }

    public Long getId() {
        return id;
    }

    /** 仅用于测试/迁移场景显式指定主键;业务代码不要调用。 */
    public void setId(Long id) {
        this.id = id;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public Long getCreateBy() {
        return createBy;
    }

    public void setCreateBy(Long createBy) {
        this.createBy = createBy;
    }

    public Long getUpdateBy() {
        return updateBy;
    }

    public void setUpdateBy(Long updateBy) {
        this.updateBy = updateBy;
    }
}
