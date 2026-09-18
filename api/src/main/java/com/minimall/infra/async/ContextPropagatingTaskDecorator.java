package com.minimall.infra.async;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 把提交线程的 MDC(traceId 等)拷贝到执行线程(架构文档 6.3、7.2、1.2)。
 *
 * <p><b>为什么必须有它</b>:MDC 是 ThreadLocal,线程池 / {@code @Async} 里的任务拿不到提交线程的 MDC,
 * 异步代码打出来的日志 traceId 会是空的 —— 而链路一断,恰好丢失的是最难复现的异步那段。
 *
 * <p><b>只拷贝 MDC,不拷贝租户/审计上下文</b>,这是刻意的(6.3 的三条硬约束):
 * 租户上下文必须在**目标线程内部**显式建立({@code TenantContext.runAsTenant}),
 * 审计身份要靠调用方把快照(4.12)当参数传进异步方法。如果这里顺手把 {@code TenantContext} 也带过去,
 * "忘了设置租户"就会静默变成"看起来正常",而它的背面是"复用到上一个请求的租户"这类串号事故 ——
 * 隔离失效必须是响的,不能是哑的。
 *
 * <p><b>执行完必须恢复现场</b>:线程池会复用线程,不清理就会把这次请求的 traceId 串到下一个任务上
 * (与 4.12 清理 ThreadLocal 是同一个理由)。这里恢复"进入前的值"而不是直接 clear,
 * 是为了兼容"池中池"式的嵌套提交,也避免把外层本该保留的 MDC 抹掉。
 */
public class ContextPropagatingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            if (callerContext != null) {
                MDC.setContextMap(callerContext);
            }
            try {
                runnable.run();
            } finally {
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
