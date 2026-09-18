package com.minimall.infra.async;

import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ContextPropagatingTaskDecorator} 的行为验证(架构文档 6.3、1.2、8.4)。
 *
 * <p>纯单元测试:不启动容器,直接在当前线程上跑"装饰后的任务"来观察上下文,
 * 因为要验证的恰恰是"任务执行期间/之后线程上的 ThreadLocal 是什么状态"。
 *
 * <p>用直接调用而不是丢进线程池的原因:虚拟线程模式下每个任务都是新线程,池里的"线程复用"
 * 现象观察不到;而"恢复现场"这条逻辑的正确性(以及它的反面——串号)必须能稳定复现。
 */
class ContextPropagatingTaskDecoratorTest {

    private final ContextPropagatingTaskDecorator decorator = new ContextPropagatingTaskDecorator();

    @AfterEach
    void clear() {
        MDC.clear();
        TenantContext.clear();
        AuditContext.clear();
    }

    @Test
    @DisplayName("提交线程的 MDC 能被任务看到(traceId 不断链)")
    void propagatesCallerMdc() {
        MDC.put("traceId", "trace-abc");
        AtomicReference<String> seenInTask = new AtomicReference<>();

        decorator.decorate(() -> seenInTask.set(MDC.get("traceId"))).run();

        assertThat(seenInTask.get()).isEqualTo("trace-abc");
    }

    @Test
    @DisplayName("任务结束后恢复现场:同一个线程再跑下一个任务时,不会带上一个任务的 traceId")
    void restoresCallerMdcAfterTask() {
        MDC.put("traceId", "trace-first");
        AtomicReference<String> secondTaskSees = new AtomicReference<>();

        // 模拟线程池复用:同一个线程先后执行两个被装饰过的任务,第二个任务提交时线程上没有 traceId
        decorator.decorate(() -> {
            assertThat(MDC.get("traceId")).isEqualTo("trace-first");
        }).run();

        MDC.clear();
        decorator.decorate(() -> secondTaskSees.set(MDC.get("traceId"))).run();

        assertThat(secondTaskSees.get()).as("上一个请求的 traceId 泄漏到下一个任务,日志会指向错误的请求")
                .isNull();
    }

    @Test
    @DisplayName("跨线程时:MDC 被拷过去,租户/审计上下文留在原线程(隔离靠异步方法内部显式建立)")
    void propagatesOnlyMdcAcrossThreads() throws Exception {
        TenantContext.setTenantId(123L);
        AuditContext.bind(new AuditContext(123L, 456L, "127.0.0.1", "trace-x"));
        MDC.put("traceId", "trace-x");
        AtomicReference<String> traceSeenInTask = new AtomicReference<>();
        AtomicReference<Long> tenantSeenInTask = new AtomicReference<>();
        AtomicReference<Long> userSeenInTask = new AtomicReference<>();

        // 必须换一条线程才能观察"哪些上下文没被带过去":同一线程里 ThreadLocal 本来就看得到
        Thread worker = new Thread(decorator.decorate(() -> {
            traceSeenInTask.set(MDC.get("traceId"));
            tenantSeenInTask.set(TenantContext.getTenantId());
            userSeenInTask.set(AuditContext.currentUserId());
        }), "decorator-test");
        worker.start();
        worker.join(5000);

        assertThat(traceSeenInTask.get()).as("MDC 是显式拷贝的,所以看得到").isEqualTo("trace-x");
        assertThat(tenantSeenInTask.get())
                .as("顺手把租户带过去会让\"忘记设置租户\"静默变成\"看起来正常\",另一面是串到上一个请求的租户")
                .isNull();
        assertThat(userSeenInTask.get()).as("审计身份必须由调用方当参数传进去(6.3)").isNull();
    }
}
