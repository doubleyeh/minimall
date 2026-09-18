package com.minimall.domain.sys;

import com.minimall.domain.OwnedEntity;
import jakarta.persistence.PrePersist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 归属人非空校验(架构文档 5.3 的硬约束)。
 *
 * <p>实现要点:本监听器通过 {@code @EntityListeners} 声明在 {@code BaseTenantEntity} 上、
 * 而 {@code BaseAuditEntity} 上声明的是 Spring Data 的 {@code AuditingEntityListener}。
 * JPA 规定同一继承链上的监听器按"父类先、子类后"的顺序调用,所以这里执行时
 * {@code create_by} 已经由审计填充写好 —— 顺序是这段逻辑能成立的前提,不要调整声明位置。
 *
 * <p>不在 {@code @PrePersist} 里"顺手帮忙填 create_by":审计字段的唯一来源是
 * {@link com.minimall.infra.audit.AuditContext}(4.4、4.12)。在这里补填等于开第二条取值的路,
 * 一旦两处逻辑不一致,排查成本远高于直接失败。
 */
public class TenantOwnershipListener {

    private static final Logger log = LoggerFactory.getLogger(TenantOwnershipListener.class);

    @PrePersist
    void checkOwner(Object entity) {
        if (!(entity instanceof OwnedEntity)) {
            return;
        }
        if (entity instanceof BaseAuditEntity auditEntity && auditEntity.getCreateBy() == null) {
            String entityName = entity.getClass().getSimpleName();
            log.error("归属人缺失,已阻断写入: entity={}", entityName);
            throw new IllegalStateException(entityName + " 标注了 OwnedEntity,但写入时 create_by 为空;"
                    + "异步/无请求场景必须显式传入 AuditContext 快照后再落库(见架构文档 4.12/5.3)");
        }
    }
}
