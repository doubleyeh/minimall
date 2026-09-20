package com.minimall.sys.service.support;

import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.infra.tenant.TenantLookup;
import com.minimall.infra.tenant.TenantSnapshot;
import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import com.minimall.sys.service.TenantService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户查询与它的缓存(架构文档 4.1、4.11)。
 *
 * <p>这个类是**登录路径的第一步**:登录请求只带 {@code tenantCode},靠它定位租户;
 * 刷新令牌则只带 {@code tenantId}。两条路都要经过这里的缓存,而缓存一旦出错,
 * 表现是"某个租户的账号突然登不进来"——因为错误文案被刻意统一成"用户名或密码错误"(4.1),
 * 日志里什么都看不出来。
 *
 * <p>三个非平凡的关注点:
 * <ol>
 *   <li><b>两级缓存</b>:{@code byCode} 先查"编码 → id"的索引,再走 {@code byId} 查快照。
 *       所以一次成功登录会命中两个键,任一环失效都会退回数据库查询(可用但变慢),
 *       而**任一环拿到脏数据**才是真正的问题</li>
 *   <li><b>禁用租户必须立刻生效</b>:靠 {@code evict} 把两个键一起删掉。少删一个(比如只删快照、
 *       忘了索引)的结果是"租户被禁用了还能登进来",直到缓存自然过期</li>
 *   <li><b>缓存里的时间是"时刻"而不是"格式"</b>:快照把 {@code expireTime} 编码成毫秒数,
 *       解码要还原成同一时刻。差了时区或精度会让租户的到期时间漂移</li>
 * </ol>
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantLookupIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long SEED_ADMIN_ID = 1L;
    private static final long FULL_PACKAGE_ID = 1L;
    private static final String PLATFORM_TENANT_CODE = "platform";
    private static final String CODE_INDEX_PREFIX = "tenant:id:";
    private static final String SNAPSHOT_PREFIX = "tenant:status:";

    @Autowired
    private TenantLookup tenantLookup;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private StringRedisTemplate redis;

    private Long tenantId;
    private String tenantCode;
    /** 建租户时写的到期时间:用来验证缓存往返后时刻不变。 */
    private LocalDateTime expireTime;

    @BeforeAll
    void createTenantForCacheTests() {
        tenantCode = "lookup-" + suffix();
        // 到期时间刻意不取整(带秒与纳秒):如果编码解码丢了精度,这里就会露出来
        expireTime = LocalDateTime.now().plusDays(30).withNano(123_000_000);
        TenantCreateResponse created = asSuperUser(() -> tenantService.create(new TenantCreateRequest(
                tenantCode, "租户查询用例租户", FULL_PACKAGE_ID, expireTime,
                "lookupadmin" + suffix(), "用例管理员", null)));
        tenantId = created.tenantId();
    }

    @BeforeEach
    void clearTenantCache() {
        // 每个用例都从"缓存为空"开始,才能确定某条路径真的走了数据库或真的走了缓存
        redis.delete(redis.keys("tenant:*"));
    }

    // ================================================================ 读取与缓存

    @Test
    @DisplayName("按编码查:未命中时回源并写两级缓存,第二次走缓存也能返回同样的快照")
    void byCodeReadsThroughAndCaches() {
        Optional<TenantSnapshot> fromDatabase = tenantLookup.byCode(PLATFORM_TENANT_CODE);
        assertThat(fromDatabase).as("种子租户必须能按编码查到 —— 登录第一步就靠它").isPresent();
        TenantSnapshot first = fromDatabase.orElseThrow();
        assertThat(first.id()).isEqualTo(PLATFORM_TENANT_ID);
        assertThat(first.tenantCode()).isEqualTo(PLATFORM_TENANT_CODE);
        assertThat(first.usable()).isTrue();

        // 两级缓存都要写上:只写快照的话,下一次 byCode 仍然要查库
        assertThat(redis.opsForValue().get(CODE_INDEX_PREFIX + PLATFORM_TENANT_CODE))
                .as("编码 → id 的索引").isNotNull();
        assertThat(redis.opsForValue().get(SNAPSHOT_PREFIX + PLATFORM_TENANT_ID)).isNotNull();
        assertThat(redis.getExpire(SNAPSHOT_PREFIX + PLATFORM_TENANT_ID))
                .as("必须有 TTL,否则租户状态永远不会刷新").isPositive();

        // 第二次:先命中索引、再命中快照(这条路径不查库)
        TenantSnapshot fromCache = tenantLookup.byCode(PLATFORM_TENANT_CODE).orElseThrow();
        assertThat(fromCache.id()).isEqualTo(first.id());
        assertThat(fromCache.tenantCode()).isEqualTo(first.tenantCode());
        assertThat(fromCache.status()).isEqualTo(first.status());
        assertThat(fromCache.packageId()).isEqualTo(first.packageId());
    }

    @Test
    @DisplayName("按 id 查:到期时间经过缓存编码解码后与库里的时刻一致(不偏时区)")
    void byIdRoundTripsExpireTimeThroughCache() {
        TenantSnapshot fromDatabase = tenantLookup.byId(tenantId).orElseThrow();
        assertThat(fromDatabase.expireTime()).as("到期时间必须读得回来").isNotNull();
        assertThat(fromDatabase.usable()).isTrue();

        // 对照的是**库里的值**而不是建租户时传进来的值:列是 DATETIME(秒精度),
        // 纳秒在建租户那一刻就被截断了 —— 那是库的行为,与缓存无关。
        // 这里要验的是缓存往返不改变时刻(编码成毫秒再解码,偏时区就会错)
        LocalDateTime stored = fromDatabase.expireTime();
        TenantSnapshot fromCache = tenantLookup.byId(tenantId).orElseThrow();
        assertThat(fromCache.expireTime())
                .as("缓存里存的是毫秒数,解码要还原成同一时刻;偏了时区,租户会提前或延后到期")
                .isEqualTo(stored);
        assertThat(fromCache.tenantCode()).isEqualTo(tenantCode);
        assertThat(fromCache.status()).isEqualTo(fromDatabase.status());
        assertThat(fromCache.packageId()).isEqualTo(FULL_PACKAGE_ID);
    }

    @Test
    @DisplayName("缓存里是脏数据时不能抛异常:索引坏掉要回源,快照坏掉按不可用处理")
    void corruptedCacheDoesNotThrow() {
        // ① 索引值不是数字:解析失败后必须回源查库,而不是抛 NumberFormatException 让登录 500
        redis.opsForValue().set(CODE_INDEX_PREFIX + PLATFORM_TENANT_CODE, "not-a-number");
        assertThat(tenantLookup.byCode(PLATFORM_TENANT_CODE))
                .as("脏索引要退回数据库查询")
                .isPresent()
                .get()
                .extracting(TenantSnapshot::id)
                .isEqualTo(PLATFORM_TENANT_ID);

        // ② 快照被截断(只剩编码一段):解码不能抛异常。
        //    注意这里的实际行为:解出来的快照 status 为 null,于是 usable() 为 false ——
        //    即"脏快照 = 这个租户暂时不可用",直到 TTL 到期才自愈。
        //    这是可接受的(缓存值损坏本就少见,且失败是自愈的、有界的),
        //    但如果将来给缓存格式加版本号,这里应当改成"解码失败就回源"
        redis.opsForValue().set(SNAPSHOT_PREFIX + PLATFORM_TENANT_ID, "platform");
        TenantSnapshot decoded = tenantLookup.byId(PLATFORM_TENANT_ID).orElseThrow();
        assertThat(decoded.tenantCode()).isEqualTo(PLATFORM_TENANT_CODE);
        assertThat(decoded.status()).as("截断的载荷解不出状态").isNull();
        assertThat(decoded.usable()).as("解不出状态的租户一律按不可用处理(4.11)").isFalse();

        // 必须把脏值清掉:它落在**种子平台租户**上,而缓存是全局共享的 ——
        // 留着会让别的测试类在 TTL 内拿到一个"不可用"的平台租户,表现成一片莫名其妙的登录失败
        redis.delete(CODE_INDEX_PREFIX + PLATFORM_TENANT_CODE);
        redis.delete(SNAPSHOT_PREFIX + PLATFORM_TENANT_ID);
    }

    // ================================================================ 失效

    @Test
    @DisplayName("禁用租户后必须立刻读不到旧的启用状态(evict 两个键都要删)")
    void evictMakesDisabledTenantVisibleImmediately() {
        assertThat(tenantLookup.byCode(tenantCode).orElseThrow().usable())
                .as("前置条件:租户现在是启用的,且已进缓存")
                .isTrue();
        assertThat(redis.opsForValue().get(SNAPSHOT_PREFIX + tenantId)).isNotNull();

        asSuperUser(() -> {
            tenantService.changeStatus(tenantId, 0);
            return null;
        });

        assertThat(tenantLookup.byCode(tenantCode).orElseThrow().usable())
                .as("""
                        禁用必须立刻体现在查询结果上。若只删快照、忘了删"编码 → id"索引,
                        这里会先命中索引再回源到已删除的快照键 —— 那种情况下靠的是回源,
                        而只要快照键还在,拿到的就是旧的启用状态,表现为"停用的租户还能登录"。""")
                .isFalse();

        // 恢复启用:这个租户是整类共用的(@BeforeAll 建一次),
        // 停着不恢复会让同类其它用例依赖的执行顺序变成一个隐藏前提
        asSuperUser(() -> {
            tenantService.changeStatus(tenantId, 1);
            return null;
        });
        assertThat(tenantLookup.byCode(tenantCode).orElseThrow().usable()).isTrue();
    }

    @Test
    @DisplayName("evict:两个键一起清掉;传 null 不抛异常")
    void evictRemovesBothKeys() {
        TenantSnapshot snapshot = tenantLookup.byCode(tenantCode).orElseThrow();
        assertThat(redis.opsForValue().get(CODE_INDEX_PREFIX + tenantCode)).isNotNull();
        assertThat(redis.opsForValue().get(SNAPSHOT_PREFIX + tenantId)).isNotNull();

        tenantLookup.evict(snapshot);

        assertThat(redis.opsForValue().get(CODE_INDEX_PREFIX + tenantCode)).as("索引要删").isNull();
        assertThat(redis.opsForValue().get(SNAPSHOT_PREFIX + tenantId)).as("快照要删").isNull();

        // 清掉之后仍能查到(回源),说明删除只是失效而不是把数据弄丢
        assertThat(tenantLookup.byId(tenantId)).isPresent();

        tenantLookup.evict(null);
    }

    // ================================================================ 边界

    @Test
    @DisplayName("查不到与参数为空:都返回空而不是抛异常")
    void unknownTenantAndNullArgumentsReturnEmpty() {
        assertThat(tenantLookup.byCode("no-such-tenant-" + suffix()))
                .as("不存在的编码要让调用方给出统一的登录失败提示(4.1:不区分原因)")
                .isEmpty();
        assertThat(tenantLookup.byId(999999L)).isEmpty();

        // 参数为空必须是 Optional.empty:登录与刷新都可能拿到空值,
        // 在这里抛异常会让"没传租户编码"变成 500 而不是一个正常的登录失败
        assertThat(tenantLookup.byCode(null)).isEmpty();
        assertThat(tenantLookup.byCode("   ")).isEmpty();
        assertThat(tenantLookup.byId(null)).isEmpty();
    }

    // ---------------------------------------------------------------- 辅助

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private <T> T asSuperUser(Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, SEED_ADMIN_ID, "127.0.0.1", "tenant-lookup-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }
}
