package com.minimall.service.sys.support;

import com.minimall.domain.sys.Tenant;
import com.minimall.domain.sys.repository.TenantRepository;
import com.minimall.infra.tenant.TenantLookup;
import com.minimall.infra.tenant.TenantProperties;
import com.minimall.infra.tenant.TenantSnapshot;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 租户查询与状态缓存(架构文档 4.11、7.1.1)。
 *
 * <p>为什么必须缓存:{@code TenantWebFilter} 每个请求都要校验租户状态,直接查库会把 {@code tenant}
 * 表变成热点;而登录接口按 {@code tenantCode} 查租户更是会被撞库脚本反复打。
 *
 * <p>两张 key 的关系:{@code tenant:id:{code}} → id,{@code tenant:status:{id}} → 快照。
 * 两张都设 60 秒 TTL(可配),**禁用租户时由调用方主动删除**(4.11 的"立即生效"),
 * TTL 只是"万一漏删"的兜底收敛时间。
 *
 * <p>快照编码用竖线分隔的字符串而不是 JSON:字段只有四个,而这张表每个请求都要读,
 * 没必要为它引入序列化格式与依赖(理由同 {@code RefreshTokenStore})。
 */
@Service
public class TenantLookupImpl implements TenantLookup {

    private static final String CODE_INDEX_PREFIX = "tenant:id:";
    private static final String SNAPSHOT_PREFIX = "tenant:status:";
    private static final String DELIMITER = "|";

    private final TenantRepository tenantRepository;
    private final StringRedisTemplate redis;
    private final TenantProperties properties;

    public TenantLookupImpl(TenantRepository tenantRepository, StringRedisTemplate redis, TenantProperties properties) {
        this.tenantRepository = tenantRepository;
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public Optional<TenantSnapshot> byCode(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return Optional.empty();
        }
        String cachedId = redis.opsForValue().get(CODE_INDEX_PREFIX + tenantCode);
        if (cachedId != null) {
            Long tenantId = parseLong(cachedId);
            if (tenantId != null) {
                return byId(tenantId);
            }
        }
        return tenantRepository.findByTenantCode(tenantCode).map(this::cacheAndConvert);
    }

    @Override
    public Optional<TenantSnapshot> byId(Long tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        String cached = redis.opsForValue().get(SNAPSHOT_PREFIX + tenantId);
        if (cached != null) {
            return Optional.of(decode(tenantId, cached));
        }
        return tenantRepository.findById(tenantId).map(this::cacheAndConvert);
    }

    @Override
    public void evict(TenantSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        redis.delete(SNAPSHOT_PREFIX + snapshot.id());
        if (snapshot.tenantCode() != null) {
            redis.delete(CODE_INDEX_PREFIX + snapshot.tenantCode());
        }
    }

    private TenantSnapshot cacheAndConvert(Tenant tenant) {
        TenantSnapshot snapshot = new TenantSnapshot(
                tenant.getId(), tenant.getTenantCode(), tenant.getStatus(), tenant.getExpireTime(), tenant.getPackageId());
        Duration ttl = Duration.ofSeconds(properties.tenantStatusCacheSeconds());
        redis.opsForValue().set(SNAPSHOT_PREFIX + snapshot.id(), encode(snapshot), ttl);
        redis.opsForValue().set(CODE_INDEX_PREFIX + snapshot.tenantCode(), String.valueOf(snapshot.id()), ttl);
        return snapshot;
    }

    private String encode(TenantSnapshot snapshot) {
        return String.join(DELIMITER,
                snapshot.tenantCode() == null ? "" : snapshot.tenantCode(),
                String.valueOf(snapshot.status()),
                snapshot.expireTime() == null ? "" : String.valueOf(toMillis(snapshot.expireTime())),
                snapshot.packageId() == null ? "" : String.valueOf(snapshot.packageId()));
    }

    private TenantSnapshot decode(Long tenantId, String value) {
        String[] parts = value.split("\\" + DELIMITER, -1);
        String code = parts.length > 0 ? parts[0] : "";
        Integer status = parts.length > 1 ? parseInteger(parts[1]) : null;
        LocalDateTime expireTime = parts.length > 2 && !parts[2].isEmpty()
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(parts[2])), ZoneId.systemDefault())
                : null;
        Long packageId = parts.length > 3 ? parseLong(parts[3]) : null;
        // id 不写进编码(它就在 key 上),这里必须回填:调用方会用它做 evict
        return new TenantSnapshot(tenantId, code, status, expireTime, packageId);
    }

    private long toMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private Long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
