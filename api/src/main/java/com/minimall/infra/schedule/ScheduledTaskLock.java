package com.minimall.infra.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 定时任务的多实例互斥(架构文档 6.2)。
 *
 * <p><b>解决什么</b>:{@code @Scheduled} 在每个实例上都会触发,多实例部署时同一批数据会被处理多遍。
 * 对"超时关单""自动收货"这类任务,重复跑的后果不是慢一点,而是**同一笔订单被关两次、库存被回补两次**。
 *
 * <p><b>为什么用 Redis 而不是调度中心</b>:项目已经强依赖 Redis(缓存、令牌、限流、单号),
 * 加一个锁是零新增组件;调度中心是另一个运维对象,等到任务真的多到需要可视化编排时再上。
 *
 * <p><b>为什么锁要带 TTL</b>:实例被强杀时锁必须能自己消失,否则这个任务再也不会有人跑 ——
 * 而且没有任何告警会告诉你"它停了"。TTL 就是"最坏情况下任务停多久"这个权衡,见 {@link ScheduleProperties}。
 *
 * <p><b>为什么释放要带 token</b>:TTL 到点后别的实例会接手,这时原来那个实例如果才慢悠悠结束,
 * 直接 DEL 会把**别人的锁**删掉,于是第三个实例又跑起来。所以释放前要比对自己那把钥匙。
 */
@Component
public class ScheduledTaskLock {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskLock.class);
    private static final String KEY_PREFIX = "task:lock:";

    /** 比对自己那把钥匙才删:不比对就可能删掉"TTL 过期后别人抢到的"锁。 */
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ScheduleProperties properties;

    public ScheduledTaskLock(StringRedisTemplate redis, ScheduleProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 抢到锁才执行;没抢到就跳过(别的实例正在跑)。
     *
     * @return true 表示本次真的执行了
     */
    public boolean runIfNotLocked(String taskName, Runnable task) {
        String token = UUID.randomUUID().toString();
        String key = KEY_PREFIX + taskName;
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token,
                Duration.ofSeconds(properties.taskLockSeconds()));
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("任务「{}」已有实例在跑,本实例跳过", taskName);
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            // 任务失败也要放锁:不放的话这个任务在 TTL 到期前谁都不会再跑
            release(taskName, token);
        }
    }

    /**
     * 释放锁。钥匙不对就不动(那是别的实例的锁)。
     */
    public void release(String taskName, String token) {
        Long deleted = redis.execute(RELEASE_IF_OWNER, List.of(KEY_PREFIX + taskName), token);
        if (deleted == null || deleted == 0L) {
            log.warn("任务「{}」的锁已不属于本实例(多半是执行时间超过了锁的 TTL),未释放", taskName);
        }
    }
}
