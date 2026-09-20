package com.minimall.infra.id;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 雪花 ID 生成器(架构文档 4.3)。
 *
 * <p><b>为什么这个类值得专门的用例</b>:它出错的表现是**产生重复 ID** —— 也就是数据损坏,
 * 而不是一个能被业务断言抓到的错误。而它出错的时机全是"罕见分支":时钟回拨、
 * 同一毫秒内序列号用满、两个实例配了同一个 workerId。这些平时一条日志都不会有。
 *
 * <p>三条断言口径:
 * <ol>
 *   <li><b>位布局</b>:时间戳/机房/机器/序列号各占几位、谁在高位。改了布局不会报错,
 *       但新 ID 会与库里已有的 ID 语义不一致(比如按时间排序失效)</li>
 *   <li><b>并发唯一性</b>:4000 个/毫秒的容量用满时必须去等下一毫秒,而不是回绕成重复值</li>
 *   <li><b>时钟回拨宁可拒绝</b>:上层报错可以告警,生成重复 ID 无法挽回</li>
 * </ol>
 *
 * <p>本类是**纯单元测试**(不连库、不连 Redis),因此不打 integration 标签。
 * 它会在同一 JVM 里与集成测试一起跑,所以对静态实例的改动必须还原(见 {@link #restoreStaticInstance()})。
 */
class SnowflakeIdGeneratorTest {

    /** 与 SnowflakeIdGenerator.EPOCH 一致:2026-01-01T00:00:00Z。布局用例刻意重复这个常量。 */
    private static final long EPOCH = 1767225600000L;
    private static final int SEQUENCE_BITS = 12;
    private static final int WORKER_BITS = 5;
    private static final int DATACENTER_BITS = 5;

    /** 本类构造生成器会覆盖那个静态引用,先存下来,用例结束必须还原。 */
    private Object originalInstance;

    @BeforeEach
    void rememberStaticInstance() throws Exception {
        originalInstance = readStaticInstance();
    }

    @AfterEach
    void restoreStaticInstance() throws Exception {
        Field field = SnowflakeIdGenerator.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, originalInstance);
    }

    // ================================================================ 配置校验

    @Test
    @DisplayName("配置:缺省填 1,超出 0-31 必须在启动时就失败")
    void propertiesValidateWorkerAndDatacenter() {
        SnowflakeProperties defaults = new SnowflakeProperties(null, null);
        assertThat(defaults.workerId()).as("不配时给一个可用值,而不是让整个应用起不来").isEqualTo(1);
        assertThat(defaults.datacenterId()).isEqualTo(1);

        // 边界值必须能过:5 bit 就是 0-31
        assertThat(new SnowflakeProperties(31, 31).workerId()).isEqualTo(31);

        assertThatThrownBy(() -> new SnowflakeProperties(-1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worker-id")
                .as("配置写错要在启动时炸掉:两个实例共用同一个 workerId 会静默生成重复 ID")
                .hasMessageContaining("0-31");
        assertThatThrownBy(() -> new SnowflakeProperties(1, 32))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datacenter-id");
    }

    // ================================================================ 位布局

    @Test
    @DisplayName("位布局:机房、机器、序列号各就各位,时间戳部分与当前时间对得上")
    void idLayoutEncodesDatacenterWorkerAndSequence() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(new SnowflakeProperties(7, 3));
        long id = SnowflakeIdGenerator.nextId();

        assertThat((id >> SEQUENCE_BITS) & 31L)
                .as("低 12 位之后是 workerId")
                .isEqualTo(7L);
        assertThat((id >> (SEQUENCE_BITS + WORKER_BITS)) & 31L)
                .as("再往上是 datacenterId")
                .isEqualTo(3L);

        long timestampPart = id >> (SEQUENCE_BITS + WORKER_BITS + DATACENTER_BITS);
        long generatedAt = EPOCH + timestampPart;
        assertThat(generatedAt)
                .as("高位是相对纪元的毫秒数;布局改了不会报错,但按 ID 排序会失效")
                .isBetween(System.currentTimeMillis() - 5_000, System.currentTimeMillis() + 5_000);
        assertThat(id).as("ID 必须为正数:负数会让很多按 id 排序/取模的写法出错").isPositive();
    }

    // ================================================================ 并发与回绕

    @Test
    @DisplayName("并发唯一性:序列号在同一毫秒内用满时要等下一毫秒,不能回绕成重复值")
    void idsAreUniqueUnderConcurrency() throws Exception {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(new SnowflakeProperties(5, 9));
        int threads = 8;
        int perThread = 3_000;
        // 24000 个 ID 远超"每毫秒 4096 个"的容量,必然会走满序列号并自旋等下一毫秒
        Set<Long> ids = Collections.synchronizedSet(new HashSet<>());
        List<Throwable> failures = Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        for (int i = 0; i < perThread; i++) {
                            ids.add(SnowflakeIdGenerator.nextId());
                        }
                    } catch (Throwable ex) {
                        failures.add(ex);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("生成应当在 30 秒内结束").isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures).as("并发生成不该抛异常").isEmpty();
        assertThat(ids)
                .as("""
                        重复 ID 就是数据损坏,而且不会当场报错 —— 等到某张表的主键冲突或某条数据被覆盖时
                        已经晚了。这一条是本类最重要的断言:有界序列号用满时必须自旋等下一毫秒。""")
                .hasSize(threads * perThread);
    }

    // ================================================================ 时钟回拨

    @Test
    @DisplayName("时钟回拨:拒绝生成(抛异常),而不是生成可能重复的 ID")
    void clockRollbackIsRejected() throws Exception {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(new SnowflakeProperties(1, 1));
        SnowflakeIdGenerator.nextId();

        // 把"上一次生成时间"推到未来,等价于"时钟往回拨了":不必真的改系统时钟
        setLongField(generator, "lastTimestamp", System.currentTimeMillis() + 60_000);

        assertThatThrownBy(SnowflakeIdGenerator::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("时钟回拨");

        // 回拨期间会持续拒绝:这个取舍是有意的 —— 上层报错/告警可以处理,重复 ID 无法挽回。
        // (把 lastTimestamp 恢复成正常值就恢复生成,见 afterEach 里对静态实例的还原)
        assertThatThrownBy(SnowflakeIdGenerator::nextId).isInstanceOf(IllegalStateException.class);
    }

    // ================================================================ 未初始化

    @Test
    @DisplayName("未初始化时静态入口要立刻失败,不能静默返回 0 或 null")
    void staticNextIdFailsFastWhenGeneratorIsNotInitialized() throws Exception {
        Field field = SnowflakeIdGenerator.class.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);

        // 实体基类的 @PrePersist 回调走的就是这个静态入口。它返回 0 或 null 会写出坏数据,
        // 所以宁可抛异常 —— 这条分支也是"启动顺序错了"的现场提示
        assertThatThrownBy(SnowflakeIdGenerator::nextId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未初始化");
    }

    // ---------------------------------------------------------------- 反射辅助

    private Object readStaticInstance() throws Exception {
        Field field = SnowflakeIdGenerator.class.getDeclaredField("instance");
        field.setAccessible(true);
        return field.get(null);
    }

    private void setLongField(SnowflakeIdGenerator generator, String name, long value) throws Exception {
        Field field = SnowflakeIdGenerator.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(generator, value);
    }
}
