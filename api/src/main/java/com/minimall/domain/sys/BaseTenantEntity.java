package com.minimall.domain.sys;

import com.minimall.infra.tenant.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import org.hibernate.annotations.Filter;

/**
 * 租户级实体基类(架构文档 4.6):带租户过滤 + 审计字段,tenant_id 非空且自动回填。
 *
 * <p>适用:{@code sys_user}、{@code sys_dept}、{@code sys_role}、{@code sys_oper_log},
 * 以及以后的业务表(清单见 4.6.1)。
 *
 * <p>三点实现说明:
 * <ol>
 *   <li>{@code @Filter(tenantFilter)} 声明在基类上,被所有子类继承 —— 走基类的实体自动受租户隔离保护,
 *       **不走基类的实体就没有过滤**(这也是 4.6 强调"不能混用基类"的原因)</li>
 *   <li>{@code tenant_id} 的自动回填用基类上的 {@code @PrePersist} 回调实现(4.5)。
 *       与文档措辞里的"实体监听器"是同一件事,区别只是回调方法写在基类上而不是独立监听器里 ——
 *       这样可以直接读写字段,不必为一个字段额外开接口</li>
 *   <li>回填取不到上下文时**抛异常阻断**,不允许写入 tenant_id 为空的脏数据</li>
 * </ol>
 *
 * <p>注意 {@code @EntityListeners(TenantOwnershipListener.class)} 没有覆盖父类声明:
 * JPA 会把继承链上的监听器按"父类先、子类后"合并,所以审计填充(AuditingEntityListener)
 * 一定先于归属人校验执行,顺序是校验逻辑成立的前提(见 {@code TenantOwnershipListener})。
 */
@MappedSuperclass
@EntityListeners(TenantOwnershipListener.class)
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public abstract class BaseTenantEntity extends BaseAuditEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    /**
     * tenant_id 自动回填(4.5)。业务代码显式设置过就不覆盖——显式值优先,
     * 但显式设置别的租户 ID 属于跨租户写,应由业务规则禁止(现阶段没有这种需求)。
     */
    @PrePersist
    void fillTenantIdIfAbsent() {
        if (tenantId != null) {
            return;
        }
        Long currentTenantId = TenantContext.getTenantId();
        if (currentTenantId == null) {
            if (!tenantRequired()) {
                // 少数实体允许"租户未知":典型是登录失败的操作日志——此时还没识别出租户,
                // 但这条日志恰恰是排查时最有价值的。这类实体的 tenant_id 在库上也是可空的(见 V1)。
                return;
            }
            throw new IllegalStateException(getClass().getSimpleName()
                    + " 写入时 tenant_id 为空,且 TenantContext 中也没有租户。"
                    + "异步/无请求场景必须先用 TenantContext.runAsTenant(...) 进入租户上下文(见架构文档 4.5/6.3)");
        }
        tenantId = currentTenantId;
    }

    /**
     * 该实体的 tenant_id 是否必须非空。默认 true(4.5 的硬约束)。
     *
     * <p>只有"租户未知也要落库"的场景才覆盖成 false,当前仅 {@code SysOperLog}。
     * 覆盖时要同步确认建表脚本里该表的 tenant_id 是可空的,否则会在数据库层再失败一次。
     */
    protected boolean tenantRequired() {
        return true;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}
