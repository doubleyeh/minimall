package com.minimall.infra.audit;

import com.minimall.sys.domain.SysOperLog;
import com.minimall.sys.domain.repository.SysOperLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 操作日志的异步落库(架构文档 7.2)。
 *
 * <p><b>为什么用 mock 而不是连库</b>:这个类要保护的性质都跟"数据库不好用的时候会怎样"有关 ——
 * 落库失败、队列打满。真库测试只能验证顺利路径,而顺利路径恰恰是最不需要担心的那条。
 * 用 mock 可以把"写库很慢"和"写库直接抛异常"变成确定性的场景。
 *
 * <p>三条被钉住的硬约束:
 * <ol>
 *   <li>写不进去**不能影响主流程**(日志是观测手段,不能让业务接口失败)</li>
 *   <li>队列满时**丢弃**而不是退回调用线程执行(退回等于把异步写成同步,正是要避免的)</li>
 *   <li>关闭时把队列里的写完,写不完也不能卡住进程退出</li>
 * </ol>
 */
class OperLogWriterTest {

    private SysOperLogRepository repository;
    private OperLogWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(SysOperLogRepository.class);
        writer = new OperLogWriter(repository);
    }

    @AfterEach
    void tearDown() {
        // 每个用例都要收尾:它是常驻线程池,不收会留下线程
        writer.shutdown();
    }

    private OperLogEntry entry() {
        return new OperLogEntry(7L, 42L, "商城商品", "mall:goods:save", "POST /mall/admin/goods",
                "{\"goodsName\":\"测试商品\"}", 1, null, "127.0.0.1", "trace-it-1", LocalDateTime.now());
    }

    @Test
    @DisplayName("空的日志条目直接忽略,不落库也不抛异常")
    void nullEntryIsIgnored() {
        writer.write(null);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("落库时身份信息是显式赋值的,不依赖异步线程上的上下文")
    void persistsAllFieldsExplicitly() throws Exception {
        CountDownLatch saved = new CountDownLatch(1);
        ArgumentCaptor<SysOperLog> captor = ArgumentCaptor.forClass(SysOperLog.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> {
            saved.countDown();
            return invocation.getArgument(0);
        });

        writer.write(entry());
        assertThat(saved.await(5, TimeUnit.SECONDS)).as("日志应当被异步写入").isTrue();

        SysOperLog operLog = captor.getValue();
        // 异步线程里没有 TenantContext / AuditContext / 登录态,所以这些只能从入参快照带过来(4.12)。
        // 少设一个的表现是"日志表里 tenant_id 或 user_id 全是 NULL",而且不会报任何错
        assertThat(operLog.getTenantId()).isEqualTo(7L);
        assertThat(operLog.getUserId()).isEqualTo(42L);
        assertThat(operLog.getModule()).isEqualTo("商城商品");
        assertThat(operLog.getPermCode()).isEqualTo("mall:goods:save");
        assertThat(operLog.getMethod()).isEqualTo("POST /mall/admin/goods");
        assertThat(operLog.getRequestParams()).contains("测试商品");
        assertThat(operLog.getStatus()).isEqualTo(1);
        assertThat(operLog.getIp()).isEqualTo("127.0.0.1");
        assertThat(operLog.getTraceId()).as("traceId 要跟着日志走,否则这条日志在自己的链路里查不到").isEqualTo("trace-it-1");
    }

    @Test
    @DisplayName("落库失败只记警告,绝不把异常抛给调用方(日志不能拖垮业务)")
    void persistFailureDoesNotPropagate() throws Exception {
        CountDownLatch attempted = new CountDownLatch(1);
        when(repository.save(any())).thenAnswer(invocation -> {
            attempted.countDown();
            throw new IllegalStateException("模拟数据库不可用");
        });

        // 关键断言:write 不抛异常。若这里抛了,业务接口会因为"日志写不进去"而失败 ——
        // 这是"日志拖垮系统"的经典事故
        writer.write(entry());

        assertThat(attempted.await(5, TimeUnit.SECONDS)).as("异常发生在异步线程里,不该影响调用方").isTrue();
        // 尝试确实是发生过的(不是"压根没提交"),只是失败了
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("队列满时丢弃而不是退回调用线程执行(退回等于把异步写成同步)")
    void dropsInsteadOfBlockingCallerWhenQueueIsFull() throws Exception {
        CountDownLatch firstTaskStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger savedCount = new AtomicInteger();
        when(repository.save(any())).thenAnswer(invocation -> {
            savedCount.incrementAndGet();
            firstTaskStarted.countDown();
            // 撑住唯一的写入线程,让后面的提交只能进队列
            release.await(10, TimeUnit.SECONDS);
            return invocation.getArgument(0);
        });

        writer.write(entry());
        assertThat(firstTaskStarted.await(5, TimeUnit.SECONDS)).as("确认写入线程已被占住").isTrue();

        // 队列容量 2000,再加唯一的运行中任务 = 最多接受 2001 条;这里提交 2501 条,必然有被丢弃的
        for (int i = 0; i < 2500; i++) {
            // 全部不抛异常:调用方(切面)不需要 try/catch,也不该被日志拖慢
            writer.write(entry());
        }

        release.countDown();
        // 等待被接受的那些写完(1 条运行中 + 2000 条排队)
        long deadline = System.currentTimeMillis() + 10_000;
        while (savedCount.get() < 2001 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertThat(savedCount.get())
                .as("超出容量的部分必须被丢弃,而不是退回调用线程执行 —— 那会让业务线程去写库")
                .isEqualTo(2001);
    }

    @Test
    @DisplayName("关闭之后再写入也不抛异常(关闭后 execute 会被拒绝,同样要被吞掉)")
    void writeAfterShutdownDoesNotThrow() {
        writer.shutdown();

        writer.write(entry());

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("关闭时队列写不完:等 5 秒后强制中断,不卡住进程退出")
    void shutdownForcesAbandonAfterTimeout() throws Exception {
        CountDownLatch firstTaskStarted = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        when(repository.save(any())).thenAnswer(invocation -> {
            firstTaskStarted.countDown();
            try {
                neverReleased.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                // shutdownNow 会打断它 —— 这正是本用例要观察的行为
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return invocation.getArgument(0);
        });

        writer.write(entry());
        assertThat(firstTaskStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // 永远写不完的场景:shutdown 等 5 秒后必须强制中断,而不是无限等下去。
        // 进程退出被日志卡住是很常见的故障(部署时表现为"服务停不下来,最后被 SIGKILL")
        writer.shutdown();

        // 打断是发给工作线程的,要等它收到 —— 直接断言会偶发失败(断言可能先跑完)
        long deadline = System.currentTimeMillis() + 3_000;
        while (!interrupted.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(interrupted.get())
                .as("超时后必须 shutdownNow 打断阻塞任务")
                .isTrue();
    }
}
