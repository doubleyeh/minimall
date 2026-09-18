package com.minimall.infra.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 租户上下文的行为约束(架构文档 4.1)。
 *
 * <p>这些用例**不需要 Spring、不需要 DB**,直接调 set/runAsTenant 即可验证 ——
 * 这正是 4.1 把上下文做成纯 ThreadLocal 的收益:隔离机制可测试性的基础。
 */
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("默认未定租户:tenantId 为空且不是超管(4.2 的默认拒绝依赖这个初始状态)")
    void defaultStateIsUnbound() {
        assertThat(TenantContext.hasTenant()).isFalse();
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.isSuperUser()).isFalse();
    }

    @Test
    @DisplayName("setTenantId 不影响已设置的 isSuperUser 标志")
    void setTenantIdKeepsSuperFlag() {
        TenantContext.setSuperUser(true);
        TenantContext.setTenantId(1L);

        assertThat(TenantContext.getTenantId()).isEqualTo(1L);
        assertThat(TenantContext.isSuperUser()).isTrue();
    }

    @Test
    @DisplayName("runAsTenant 支持嵌套,退出内层后恢复外层上下文(4.1 的契约)")
    void runAsTenantRestoresOuterContextWhenNested() {
        TenantContext.runAsTenant(100L, false, () -> {
            assertThat(TenantContext.getTenantId()).isEqualTo(100L);

            TenantContext.runAsTenant(200L, true, () -> {
                assertThat(TenantContext.getTenantId()).isEqualTo(200L);
                assertThat(TenantContext.isSuperUser()).isTrue();
            });

            assertThat(TenantContext.getTenantId()).isEqualTo(100L);
            assertThat(TenantContext.isSuperUser()).isFalse();
        });
    }

    @Test
    @DisplayName("最外层退出后上下文被清空,不会把租户残留到线程池的下一个任务")
    void outerRunAsTenantClearsContextWhenFinished() {
        TenantContext.runAsTenant(100L, false, () -> assertThat(TenantContext.hasTenant()).isTrue());

        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.isSuperUser()).isFalse();
    }

    @Test
    @DisplayName("异常路径同样要恢复外层上下文,否则一次失败会污染后续任务")
    void runAsTenantRestoresContextOnException() {
        assertThatThrownBy(() -> TenantContext.runAsTenant(100L, false, () -> {
            throw new IllegalStateException("business failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(TenantContext.hasTenant()).isFalse();
    }

    @Test
    @DisplayName("callAsTenant 的返回值透传,且退出后上下文复位")
    void callAsTenantReturnsValueAndRestores() {
        Long result = TenantContext.callAsTenant(300L, false, TenantContext::getTenantId);

        assertThat(result).isEqualTo(300L);
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @DisplayName("clear 会同时清掉租户与超管标志(请求结束必须调)")
    void clearResetsBothTenantAndSuperFlag() {
        TenantContext.setTenantId(1L);
        TenantContext.setSuperUser(true);

        TenantContext.clear();

        assertThat(TenantContext.hasTenant()).isFalse();
        assertThat(TenantContext.isSuperUser()).isFalse();
    }
}
