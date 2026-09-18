package com.minimall.infra.audit;

import com.minimall.domain.sys.SysOperLog;
import com.minimall.domain.sys.repository.SysOperLogRepository;
import com.minimall.infra.web.TraceIdFilter;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 操作日志的异步落库(架构文档 7.2)。
 *
 * <p><b>三条不能省的约束</b>:
 * <ol>
 *   <li><b>不阻塞主流程,也不能把主流程搞挂</b>:落库失败只记 WARN。日志是观测手段,
 *       不能因为日志表写不进去就让业务接口失败——这是"日志拖垮系统"的经典事故</li>
 *   <li><b>队列必须有界</b>:无界队列在日志暴涨时会把内存吃光。这里用有界队列 + 自定义拒绝策略:
 *       队列满时**丢弃并告警**,而不是退回调用线程执行(退回等于把异步写成同步,正是要避免的)</li>
 *   <li><b>进程退出要 flush</b>:没有优雅关闭的话,最后一批日志会丢</li>
 * </ol>
 *
 * <p>为什么是单线程:日志写入是纯 IO 且允许延迟,单线程足够;线程数与 DB 连接池的关系也更好估算
 * ——每个线程都要占一个连接。这里不需要虚拟线程(它有队列在扛突发,线程数恒定)。
 */
@Component
public class OperLogWriter {

    private static final Logger log = LoggerFactory.getLogger(OperLogWriter.class);

    /** 队列容量:按"突发 2000 条日志"估算。达到上限说明写库速度跟不上,需要扩容或降级采样。 */
    private static final int QUEUE_CAPACITY = 2000;

    private final SysOperLogRepository repository;
    private final ThreadPoolExecutor executor;

    public OperLogWriter(SysOperLogRepository repository) {
        this.repository = repository;
        this.executor = new ThreadPoolExecutor(
                1, 1,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "oper-log-writer");
                    thread.setDaemon(true);
                    return thread;
                },
                new DiscardAndWarnPolicy());
    }

    /**
     * 提交一条日志。**本方法不会抛异常**,调用方(切面)不需要 try/catch。
     */
    public void write(OperLogEntry entry) {
        if (entry == null) {
            return;
        }
        try {
            executor.execute(() -> persist(entry));
        } catch (RejectedExecutionException ex) {
            log.warn("操作日志队列已满,本条日志被丢弃:module={} method={}", entry.module(), entry.method());
        }
    }

    private void persist(OperLogEntry entry) {
        // 异步线程上 MDC 是空的(7.2):把提交那一刻的 traceId 补回到本线程,
        // 这样这条日志自身产生的日志行也带着同一个 traceId,链路不断。
        // 值取自 entry 里的**快照**,不是去猜——异步线程与提交线程之间没有共享状态(4.12)。
        String previousTraceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        if (entry.traceId() != null) {
            MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, entry.traceId());
        }
        try {
            SysOperLog operLog = new SysOperLog();
            // 身份信息**显式赋值**,不依赖 4.5 的自动回填与 4.4 的审计填充
            // ——异步线程里那两个上下文的来源都是空的(见 4.12 的硬约束)
            operLog.setTenantId(entry.tenantId());
            operLog.setUserId(entry.userId());
            operLog.setModule(entry.module());
            operLog.setPermCode(entry.permCode());
            operLog.setMethod(entry.method());
            operLog.setRequestParams(entry.requestParams());
            operLog.setStatus(entry.status());
            operLog.setErrorMsg(entry.errorMsg());
            operLog.setIp(entry.ip());
            operLog.setTraceId(entry.traceId());
            repository.save(operLog);
        } catch (Exception ex) {
            // 不抛出:异步线程里抛出只会变成一句无人处理的堆栈,还会让人误以为业务失败
            log.warn("操作日志落库失败(业务已正常完成):module={} method={} cause={}",
                    entry.module(), entry.method(), ex.getMessage());
        } finally {
            // 线程是复用的,必须还原:否则这次请求的 traceId 会串到后面所有日志里(4.12 同一个理由)
            if (previousTraceId == null) {
                MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
            } else {
                MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, previousTraceId);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("操作日志队列未在 5 秒内写完,剩余 {} 条将被丢弃", executor.getQueue().size());
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    /** 队列满时的策略:丢弃 + 告警,绝不退回调用线程执行。 */
    private static final class DiscardAndWarnPolicy implements java.util.concurrent.RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
            throw new RejectedExecutionException("操作日志写入队列已满");
        }
    }
}
