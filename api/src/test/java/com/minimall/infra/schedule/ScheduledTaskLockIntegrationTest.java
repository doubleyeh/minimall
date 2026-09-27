package com.minimall.infra.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 定时任务的多实例互斥(架构文档 6.2)。
 *
 * <p>互斥失效的后果不是"慢一点",而是**同一批数据被处理两遍**:同一笔订单关两次、库存回补两次。
 * 而且它只在多实例部署时才出现,单机开发永远看不到 —— 所以必须有用例钉住。
 *
 * <p>用例里的"另一个实例"直接用 Redis 里那个 key 表示:锁的本质就是"这个 key 在不在",
 * 不需要真起第二个进程。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class ScheduledTaskLockIntegrationTest {

    private static final String KEY_PREFIX = "task:lock:";

    @Autowired
    private ScheduledTaskLock taskLock;
    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("已经有实例在跑:本次跳过,任务体一次都不执行")
    void skipsWhenAnotherInstanceHoldsTheLock() {
        String taskName = "用例任务" + System.nanoTime();
        // 模拟"另一个实例正在跑"
        redis.opsForValue().set(KEY_PREFIX + taskName, "another-instance", Duration.ofMinutes(5));
        AtomicInteger executions = new AtomicInteger();

        boolean executed = taskLock.runIfNotLocked(taskName, executions::incrementAndGet);

        assertThat(executed).isFalse();
        assertThat(executions).as("跳过就必须真的不执行,不能只记一条日志还照样跑").hasValue(0);
    }

    @Test
    @DisplayName("没人在跑就执行,执行完把锁放掉(下一次还能跑)")
    void executesAndReleases() {
        String taskName = "用例任务" + System.nanoTime();
        AtomicInteger executions = new AtomicInteger();

        assertThat(taskLock.runIfNotLocked(taskName, executions::incrementAndGet)).isTrue();
        assertThat(taskLock.runIfNotLocked(taskName, executions::incrementAndGet))
                .as("上一次结束了就该能再跑,否则任务只会跑一次")
                .isTrue();
        assertThat(executions).hasValue(2);
    }

    @Test
    @DisplayName("任务抛异常也要放锁:不放的话这个任务在 TTL 到期前谁都不会再跑")
    void releasesLockEvenWhenTaskFails() {
        String taskName = "用例任务" + System.nanoTime();

        assertThatThrownBy(() -> taskLock.runIfNotLocked(taskName, () -> {
            throw new IllegalStateException("模拟任务失败");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(redis.hasKey(KEY_PREFIX + taskName)).as("异常路径也必须放锁").isFalse();
        assertThat(taskLock.runIfNotLocked(taskName, () -> { })).isTrue();
    }

    @Test
    @DisplayName("锁过期后可以接手:实例被强杀时不能让任务永远停着")
    void takesOverAfterLockExpires() throws Exception {
        String taskName = "用例任务" + System.nanoTime();
        String key = KEY_PREFIX + taskName;
        // 用"另一个实例拿到了锁但很快过期"来模拟强杀(不等真实的 30 分钟)
        redis.opsForValue().set(key, "killed-instance", Duration.ofMillis(300));

        assertThat(taskLock.runIfNotLocked(taskName, () -> { })).as("锁还在时抢不到").isFalse();

        Thread.sleep(500);
        assertThat(taskLock.runIfNotLocked(taskName, () -> { })).as("锁过期后必须能接手").isTrue();
    }

    @Test
    @DisplayName("释放时要比对钥匙:不能把别人(接手后)的锁删掉")
    void doesNotReleaseOthersLock() {
        String taskName = "用例任务" + System.nanoTime();
        String key = KEY_PREFIX + taskName;
        // 模拟:本实例超时后另一个实例接手了,本实例这时才慢悠悠结束
        redis.opsForValue().set(key, "another-instance", Duration.ofMinutes(5));

        taskLock.release(taskName, "my-stale-token");

        assertThat(redis.opsForValue().get(key))
                .as("直接 DEL 会把别人的锁删掉,于是第三个实例又跑起来")
                .isEqualTo("another-instance");
    }

    @Test
    @DisplayName("锁带 TTL:实例被强杀时任务不会永远停着")
    void lockHasConfiguredTtl() {
        // 用 60 秒 TTL 的实例,好在任务体里直接观察到 TTL(不用真的等 30 分钟)
        ScheduledTaskLock shortLived = new ScheduledTaskLock(redis, new ScheduleProperties(60));
        String taskName = "用例任务" + System.nanoTime();
        AtomicLong ttlSeen = new AtomicLong(-1);

        shortLived.runIfNotLocked(taskName, () -> ttlSeen.set(redis.getExpire(KEY_PREFIX + taskName)));

        assertThat(ttlSeen.get())
                .as("没有 TTL(返回 -1)就意味着实例被强杀后这个任务再也不会有人跑")
                .isBetween(1L, 60L);
    }
}
