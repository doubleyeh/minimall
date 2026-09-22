package com.minimall.infra.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计字段基类的主键生成与字段读写(架构文档 4.3、4.4)。
 *
 * <p>重点只有一条:{@code @PrePersist} 的"保留显式指定的主键"。它有另一个方向(为空则生成雪花 ID),
 * 那条被所有落库用例顺带覆盖了,而**保留**这一条没有 —— 种子数据靠它稳定引用:
 * 种子用固定小整数 ID,后续版本的 {@code sys_package_menu} 要引用菜单 ID(9.5)。
 * 一旦这里改成无条件覆盖,种子数据每次重建都会换一批 ID,引用就全断了。
 *
 * <p>审计字段本身由 Spring Data JPA Auditing 填充,这里的读写断言是为了钉住字段的语义:
 * {@code createBy/createTime} 只在首次落库时写一次,{@code updateBy/updateTime} 每次变更都刷新。
 */
class BaseAuditEntityTest {

    /** 基类是抽象的,用一个最小子类承载。 */
    private static final class Sample extends BaseAuditEntity {
    }

    @Test
    @DisplayName("@PrePersist:已经显式指定的主键要保留(种子数据靠它稳定引用)")
    void assignIdKeepsExplicitId() {
        Sample entity = new Sample();
        entity.setId(42L);
        entity.assignIdIfAbsent();
        assertThat(entity.getId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("审计字段:写入后能原样读出")
    void auditFieldsRoundTrip() {
        Sample entity = new Sample();
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime updated = LocalDateTime.of(2026, 1, 2, 10, 0);

        entity.setCreateTime(created);
        entity.setUpdateTime(updated);
        entity.setCreateBy(1L);
        entity.setUpdateBy(2L);

        assertThat(entity.getCreateTime()).isEqualTo(created);
        assertThat(entity.getUpdateTime()).isEqualTo(updated);
        assertThat(entity.getCreateBy()).isEqualTo(1L);
        assertThat(entity.getUpdateBy()).isEqualTo(2L);
    }
}
