package com.minimall.infra.id;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 雪花 ID 生成器(架构文档 4.3)。
 *
 * <p>结构:41 bit 时间戳 + 5 bit datacenterId + 5 bit workerId + 12 bit 序列号。
 * 同一毫秒内序列号用完则自旋等下一毫秒;检测到时钟回拨直接抛异常拒绝生成。
 *
 * <p><b>为什么提供一个静态 {@link #nextId()}</b>:JPA 实体基类的 {@code @PrePersist} 回调不是 Spring Bean,
 * 拿不到注入的实例。所以这里在构造时登记一个静态引用,Spring 容器创建完之后静态方法即可用;
 * 未初始化就调用会立刻抛异常(而不是静默返回 0 或 null,那会写出坏数据)。
 *
 * <p><b>为什么用 ReentrantLock 而不是 synchronized</b>:架构文档 1.2——虚拟线程下 synchronized 会把
 * 载体线程钉住(pinning),失去虚拟线程的意义。
 *
 * <p>关于接入方式:文档 4.3 原文写的是自定义 {@code IdentifierGenerator}。实现时改为实体基类的
 * {@code @PrePersist} 回调,理由是 {@code org.hibernate.generator.*} 这套 SPI 在 Hibernate 6→7 之间改动频繁,
 * 而 ID 生成属于纯业务能力,不该被 ORM 内部接口绑定;效果一致(都是"持久化前生成、不依赖数据库自增"),
 * 且换 ORM 也不受影响。
 */
@Component
public class SnowflakeIdGenerator {

    /** 起始纪元:2026-01-01T00:00:00Z。41 bit 秒级时间戳约可用 69 年。 */
    private static final long EPOCH = 1767225600000L;

    private static final int WORKER_BITS = 5;
    private static final int DATACENTER_BITS = 5;
    private static final int SEQUENCE_BITS = 12;

    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final int WORKER_SHIFT = SEQUENCE_BITS;
    private static final int DATACENTER_SHIFT = SEQUENCE_BITS + WORKER_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_BITS + DATACENTER_BITS;

    private static volatile SnowflakeIdGenerator instance;

    private final long workerId;
    private final long datacenterId;
    private final ReentrantLock lock = new ReentrantLock();

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(SnowflakeProperties properties) {
        this.workerId = properties.workerId();
        this.datacenterId = properties.datacenterId();
        instance = this;
    }

    /**
     * 生成下一个 ID。
     *
     * @throws IllegalStateException 生成器未初始化,或检测到时钟回拨
     */
    public static long nextId() {
        SnowflakeIdGenerator generator = instance;
        if (generator == null) {
            throw new IllegalStateException("雪花ID生成器尚未初始化:请确认 SnowflakeIdGenerator 已被 Spring 容器创建");
        }
        return generator.next();
    }

    private long next() {
        lock.lock();
        try {
            long timestamp = System.currentTimeMillis();
            if (timestamp < lastTimestamp) {
                // 时钟回拨:宁可拒绝生成(上层报错、告警),也不生成可能重复的 ID
                throw new IllegalStateException(
                        "检测到时钟回拨,拒绝生成ID:回拨 " + (lastTimestamp - timestamp) + " ms");
            }
            if (timestamp == lastTimestamp) {
                sequence = (sequence + 1) & MAX_SEQUENCE;
                if (sequence == 0) {
                    timestamp = spinToNextMillis(timestamp);
                }
            } else {
                sequence = 0L;
            }
            lastTimestamp = timestamp;
            return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                    | (datacenterId << DATACENTER_SHIFT)
                    | (workerId << WORKER_SHIFT)
                    | sequence;
        } finally {
            lock.unlock();
        }
    }

    private long spinToNextMillis(long currentTimestamp) {
        long timestamp = currentTimestamp;
        while (timestamp <= lastTimestamp) {
            Thread.onSpinWait();
            timestamp = System.currentTimeMillis();
            if (timestamp < lastTimestamp) {
                throw new IllegalStateException(
                        "自旋等待下一毫秒时检测到时钟回拨:回拨 " + (lastTimestamp - timestamp) + " ms");
            }
        }
        return timestamp;
    }
}
