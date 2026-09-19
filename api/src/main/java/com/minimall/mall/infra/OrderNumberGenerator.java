package com.minimall.mall.infra;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 订单号 / 售后单号 / 退款单号生成(商城设计文档 3.6)。
 *
 * <p>格式:{@code 日期(8位) + 当日序列(8位,补零)},共 16 位数字;售后单号与退款单号在前面加固定前缀
 * ({@code T} / {@code R})用于人工区分。
 *
 * <p><b>序列计数刻意不按租户分</b>:如果每个租户各自从 1 开始,不同租户在同一天会生成相同的订单号。
 * 支付回调只带 {@code out_trade_no},那时还没有租户上下文 —— 全局唯一的订单号让它能唯一定位到订单,
 * 否则回调处理的第一步就变成"在多个租户里猜是哪一单"。
 *
 * <p>用 Redis {@code INCR} 而不是数据库自增:订单号要按天重置,而"当天第几单"在并发下
 * 用数据库实现需要额外加锁;Redis 的 INCR 天然原子,且订单号不是需要持久化的业务数据
 * (丢了最多是当天重号,而重号会被唯一键挡住,不会静默产生重复订单)。
 */
@Component
public class OrderNumberGenerator {

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String ORDER_SEQ_PREFIX = "mall:seq:order:";
    private static final String AFTER_SALE_SEQ_PREFIX = "mall:seq:aftersale:";
    private static final String REFUND_SEQ_PREFIX = "mall:seq:refund:";

    /** 序列号的位数。 */
    private static final long SEQ_MODULO = 100_000_000L;

    /** 计数 key 的存活时间:两天足够覆盖"跨零点仍在处理"的场景,又不至于长期占内存。 */
    private static final Duration KEY_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redis;

    public OrderNumberGenerator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String nextOrderNo() {
        return next(ORDER_SEQ_PREFIX, "");
    }

    /** 售后单号:16 位前加 {@code T}。 */
    public String nextAfterSaleNo() {
        return next(AFTER_SALE_SEQ_PREFIX, "T");
    }

    /** 退款单号:16 位前加 {@code R}。 */
    public String nextRefundNo() {
        return next(REFUND_SEQ_PREFIX, "R");
    }

    private String next(String keyPrefix, String numberPrefix) {
        String day = LocalDate.now().format(DAY_FORMAT);
        String key = keyPrefix + day;
        Long seq = redis.opsForValue().increment(key);
        if (seq == null) {
            // Redis 不可用时不能返回一个"看起来正常"的订单号 —— 重号会在写库时被唯一键拦下,
            // 但那时用户已经看到下单失败;这里直接抛出,让上层按系统异常处理
            throw new IllegalStateException("生成订单号失败:Redis 不可用");
        }
        if (seq == 1L) {
            redis.expire(key, KEY_TTL);
        }
        return numberPrefix + day + String.format("%08d", seq % SEQ_MODULO);
    }
}
