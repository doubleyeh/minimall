package com.minimall.infra.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 刷新令牌的 Redis 存取(架构文档 7.1.3 的索引结构)。
 *
 * <p>三张索引,缺一不可:
 * <ul>
 *   <li>{@code auth:rt:{token}} → 有效令牌的载荷</li>
 *   <li>{@code auth:rt:used:{token}} → 已用令牌的载荷。**重放检测必须靠它**:
 *       只用"有效令牌不存在"是判断不出重放的(过期和已用过长得一样),而且要靠里面的
 *       userId 才能定位"该撤销谁的会话"</li>
 *   <li>{@code auth:rt:user:{userId}} → 该用户当前有效的令牌集合,用于"撤销该用户全部令牌"
 *       (改密、禁用用户、禁用租户时的必需操作,见 7.1.3 的撤销时机表)</li>
 * </ul>
 *
 * <p>载荷用 Redis Hash 存字段,而不是序列化成 JSON 字符串:字段只有四个,
 * 用 Hash 可以避免把"序列化格式"变成一个需要长期兼容的契约(它不该是契约),
 * 也省掉一个 JSON 依赖进入认证链路。
 */
@Component
public class RefreshTokenStore {

    private static final String TOKEN_PREFIX = "auth:rt:";
    private static final String USED_PREFIX = "auth:rt:used:";
    private static final String USER_PREFIX = "auth:rt:user:";

    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_TENANT_ID = "tenantId";
    private static final String FIELD_SUPER = "superUser";
    private static final String FIELD_ISSUED_AT = "issuedAt";

    private final StringRedisTemplate redis;

    public RefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(String token, RefreshTokenPayload payload, Duration ttl) {
        Map<String, String> fields = toFields(payload);
        String key = TOKEN_PREFIX + token;
        redis.opsForHash().putAll(key, fields);
        redis.expire(key, ttl);
    }

    public Optional<RefreshTokenPayload> load(String token) {
        return readPayload(TOKEN_PREFIX + token);
    }

    /** 记录"该令牌已被使用过",保留到宽限期结束(7.1.3)。 */
    public void markUsed(String token, RefreshTokenPayload payload, Duration grace) {
        String key = USED_PREFIX + token;
        redis.opsForHash().putAll(key, toFields(payload));
        redis.expire(key, grace);
    }

    public Optional<RefreshTokenPayload> loadUsed(String token) {
        return readPayload(USED_PREFIX + token);
    }

    public void delete(String token) {
        redis.delete(TOKEN_PREFIX + token);
    }

    public void addToUserIndex(Long userId, String token, Duration ttl) {
        String key = USER_PREFIX + userId;
        redis.opsForSet().add(key, token);
        redis.expire(key, ttl);
    }

    public void removeFromUserIndex(Long userId, String token) {
        redis.opsForSet().remove(USER_PREFIX + userId, token);
    }

    public Set<String> tokensOf(Long userId) {
        Set<String> tokens = redis.opsForSet().members(USER_PREFIX + userId);
        return tokens == null ? Set.of() : tokens;
    }

    /**
     * 撤销该用户的全部刷新令牌。改密、禁用用户、禁用租户时必须调用(7.1.3)。
     *
     * @return 被撤销的令牌数量,便于日志与测试断言
     */
    public int revokeAllOf(Long userId) {
        Set<String> tokens = tokensOf(userId);
        for (String token : tokens) {
            redis.delete(TOKEN_PREFIX + token);
        }
        redis.delete(USER_PREFIX + userId);
        return tokens.size();
    }

    private Optional<RefreshTokenPayload> readPayload(String key) {
        Map<Object, Object> entries = redis.opsForHash().entries(key);
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        Long userId = parseLong(entries.get(FIELD_USER_ID));
        if (userId == null) {
            // 结构不完整(例如被外部写入过):当作不存在处理,不要抛异常把认证接口打成 500
            return Optional.empty();
        }
        Long tenantId = parseLong(entries.get(FIELD_TENANT_ID));
        boolean superUser = Boolean.parseBoolean(String.valueOf(entries.get(FIELD_SUPER)));
        Long issuedAtMillis = parseLong(entries.get(FIELD_ISSUED_AT));
        Instant issuedAt = issuedAtMillis == null ? Instant.EPOCH : Instant.ofEpochMilli(issuedAtMillis);
        return Optional.of(new RefreshTokenPayload(userId, tenantId, superUser, issuedAt));
    }

    private Map<String, String> toFields(RefreshTokenPayload payload) {
        Map<String, String> fields = new HashMap<>(4);
        fields.put(FIELD_USER_ID, String.valueOf(payload.userId()));
        fields.put(FIELD_TENANT_ID, payload.tenantId() == null ? "" : String.valueOf(payload.tenantId()));
        fields.put(FIELD_SUPER, String.valueOf(payload.superUser()));
        fields.put(FIELD_ISSUED_AT, String.valueOf(payload.issuedAt().toEpochMilli()));
        return fields;
    }

    private Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
