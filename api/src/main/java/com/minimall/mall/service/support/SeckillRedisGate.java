package com.minimall.mall.service.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 秒杀闸门(商城设计文档 3.12):两个计数器,一次原子判断。
 *
 * <p><b>它只负责"挡流量",不负责正确性</b>。库存与限购的真相在数据库:闸门放行后,
 * 事务里还要在"这个人 × 这个商品"的账本行锁下再校验一次。Redis 丢数据、重启、值不准,
 * 结果只是多放几个请求进数据库,不会超卖、不会超限。
 *
 * <p>两个 key:
 * <ul>
 *   <li>{@code seckill:stock:{itemId}} —— 还能抢多少(活动开始时预热)</li>
 *   <li>{@code seckill:bought:{itemId}:{customerId}} —— 这个人已经抢了多少</li>
 * </ul>
 */
@Component
public class SeckillRedisGate {

    /** 闸门结果。 */
    public enum Result {
        /** 通过,已经扣掉名额。 */
        OK,
        /** 售罄。 */
        SOLD_OUT,
        /** 超过每人限购。 */
        LIMIT_EXCEEDED,
        /** 还没有预热(需要用数据库补一次)。 */
        NOT_READY
    }

    private static final String STOCK_PREFIX = "seckill:stock:";
    private static final String BOUGHT_PREFIX = "seckill:bought:";
    private static final String HOME_PREFIX = "seckill:home:";

    /**
     * 秒杀列表的缓存时间。
     *
     * <p>列表页会被反复刷新(每次进入首页、下拉刷新、来回切页面),而场次清单本身几分钟内不会变。
     * 缓存 5 秒是为了把这批读挡在数据库外面;过期时间短到不需要失效动作也能接受 ——
     * 真正需要精确的"还剩几件"不在这里取,而是详情页读 {@code seckill:stock} 计数器。
     */
    private static final Duration HOME_TTL = Duration.ofSeconds(5);

    /**
     * 库存、限购、扣减一次做完。
     *
     * <p>必须是一个脚本:拆成"先查再扣"的话,两个请求会同时看到还有货然后各扣一次。
     * 返回 {@code -2} 表示库存 key 不存在(未预热),由调用方补数据后重试。
     */
    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local stock = redis.call('GET', KEYS[1])
            if not stock then return -2 end
            stock = tonumber(stock)
            local qty = tonumber(ARGV[1])
            if stock < qty then return 0 end
            local bought = tonumber(redis.call('GET', KEYS[2]) or '0')
            if bought + qty > tonumber(ARGV[2]) then return -1 end
            redis.call('DECRBY', KEYS[1], qty)
            redis.call('INCRBY', KEYS[2], qty)
            redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
            return 1
            """, Long.class);

    /** 回补:库存加回去、已购减回去(不会减成负数)。 */
    private static final RedisScript<Long> RESTORE = new DefaultRedisScript<>("""
            redis.call('INCRBY', KEYS[1], tonumber(ARGV[1]))
            local bought = tonumber(redis.call('GET', KEYS[2]) or '0')
            local qty = tonumber(ARGV[1])
            if bought >= qty then redis.call('DECRBY', KEYS[2], qty) else redis.call('SET', KEYS[2], 0) end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public SeckillRedisGate(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 预热(活动开始时调):把"还能抢多少"直接覆盖写入。
     *
     * <p>写的是差集而不是配额,所以手动重跑也正确;直接覆盖而不是自增,所以重复执行没有副作用。
     */
    public void prewarm(Long itemId, int remaining, Duration ttl) {
        redis.opsForValue().set(stockKey(itemId), String.valueOf(Math.max(remaining, 0)), ttl);
    }

    /** key 不在时用数据库的值补一次;补不上(别人刚补过)就返回 false。 */
    public boolean loadIfAbsent(Long itemId, int remaining, Duration ttl) {
        Boolean created = redis.opsForValue()
                .setIfAbsent(stockKey(itemId), String.valueOf(Math.max(remaining, 0)), ttl);
        return Boolean.TRUE.equals(created);
    }

    /** 剩余可抢数量;key 不存在返回 null。 */
    public Integer remaining(Long itemId) {
        String value = redis.opsForValue().get(stockKey(itemId));
        return value == null ? null : Integer.valueOf(value);
    }

    /** 占用一个名额。 */
    public Result tryAcquire(Long itemId, Long customerId, int qty, int limit, Duration ttl) {
        Long code = redis.execute(ACQUIRE, List.of(stockKey(itemId), boughtKey(itemId, customerId)),
                String.valueOf(qty), String.valueOf(limit), String.valueOf(ttl.toSeconds()));
        if (code == null) {
            return Result.NOT_READY;
        }
        return switch (code.intValue()) {
            case 1 -> Result.OK;
            case 0 -> Result.SOLD_OUT;
            case -1 -> Result.LIMIT_EXCEEDED;
            default -> Result.NOT_READY;
        };
    }

    /** 事务回滚时把名额还回去。 */
    public void restore(Long itemId, Long customerId, int qty) {
        redis.execute(RESTORE, List.of(stockKey(itemId), boughtKey(itemId, customerId)), String.valueOf(qty));
    }

    /**
     * 数据库因限购拒绝、但闸门放行了 —— 说明 Redis 的已购计数不准。
     *
     * <p>这里**不能**只把名额还回去:计数还是错的,下一个请求依然会被放行,又变成"扣→查→退"的循环。
     * 正确的修法是把已购数改写成数据库的值。
     */
    public void repairLimit(Long itemId, Long customerId, int actualBought, int refundQty) {
        redis.opsForValue().increment(stockKey(itemId), refundQty);
        redis.opsForValue().set(boughtKey(itemId, customerId), String.valueOf(Math.max(actualBought, 0)));
    }

    /** 活动结束/取消:两个 key 一起清掉。 */
    public void clear(Long itemId) {
        redis.delete(stockKey(itemId));
    }

    /** 秒杀列表(按租户的整份结果)的短期缓存;未命中返回 null。 */
    public String readHome(Long tenantId) {
        return redis.opsForValue().get(homeKey(tenantId));
    }

    public void putHome(Long tenantId, String json) {
        redis.opsForValue().set(homeKey(tenantId), json, HOME_TTL);
    }

    /** 发布/开始/结束/取消都调一次:场次清单变了,缓存 5 秒也该让位。 */
    public void clearHome(Long tenantId) {
        redis.delete(homeKey(tenantId));
    }

    private String homeKey(Long tenantId) {
        return HOME_PREFIX + tenantId;
    }

    private String stockKey(Long itemId) {
        return STOCK_PREFIX + itemId;
    }

    private String boughtKey(Long itemId, Long customerId) {
        return BOUGHT_PREFIX + itemId + ":" + customerId;
    }
}
