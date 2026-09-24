package com.minimall.infra.security;

import com.minimall.sys.api.dto.MenuTreeNode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 权限缓存的读写与失效(架构文档 5.5)。
 *
 * <p><b>失效方式:租户级版本号,不做按 key 批量删除</b>。所有权限缓存 key 里都带一个版本号,
 * 任何一次权限相关写入只做一件事:{@code INCR perm:version:{tenantId}}。旧版本号的 key 再也没人访问,
 * 靠 TTL 自然过期。
 *
 * <p>为什么不是 {@code SCAN} + {@code DEL}:①删不干净就是"权限已收回但用户还在用",属于安全问题;
 * ②key 多时开销大且不原子;③版本号是 O(1)、天然正确,实现只有一个 {@code INCR},不容易写错。
 */
@Component
public class PermissionCacheService {

    private static final String VERSION_KEY_PREFIX = "perm:version:";
    private static final String CACHE_KEY_PREFIX = "perm:v";
    private static final String FIELD_PERMS = "perms";
    private static final String FIELD_TREE = "menuTree";
    private static final String DELIMITER = ",";

    private final StringRedisTemplate redis;
    private final PermissionProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PermissionCacheService(StringRedisTemplate redis, PermissionProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 该租户当前的权限版本号。没有 key 时返回 0(用一个不存在的版本号等价于"从没失效过")。
     */
    public long currentVersion(Long tenantId) {
        String value = redis.opsForValue().get(VERSION_KEY_PREFIX + tenantId);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            // 被外部写坏:当作 0,不要抛异常把认证接口打成 500
            return 0L;
        }
    }

    /**
     * 让该租户的全部权限缓存立即失效(下一个请求就是新权限)。
     *
     * <p>调用点必须收敛到一处(见 5.5 的触发表):角色授权变更、用户角色变更、套餐变更、
     * 菜单启用/禁用/删除、角色/用户禁用。**散落在各 service 里迟早会漏一个**,漏掉的那个入口
     * 就是"权限收回了但还能用"。
     */
    public void invalidateTenant(Long tenantId) {
        redis.opsForValue().increment(VERSION_KEY_PREFIX + tenantId);
    }

    /**
     * 读缓存。读不到菜单树字段(比如升级前写入的老条目)一律当作**未命中**,让调用方重算并覆盖写 ——
     * 这样换缓存格式不需要人工清 Redis;解析失败也只退化为未命中,不把认证接口打成 500。
     */
    public Optional<CachedPermission> get(Long tenantId, Long userId) {
        long version = currentVersion(tenantId);
        Map<Object, Object> entries = redis.opsForHash().entries(cacheKey(tenantId, version, userId));
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        Object rawTree = entries.get(FIELD_TREE);
        if (rawTree == null) {
            return Optional.empty();
        }
        try {
            MenuTreeNode[] tree = objectMapper.readValue(String.valueOf(rawTree), MenuTreeNode[].class);
            return Optional.of(new CachedPermission(
                    split(String.valueOf(entries.getOrDefault(FIELD_PERMS, ""))),
                    new ArrayList<>(Arrays.asList(tree))));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public void put(Long tenantId, Long userId, CachedPermission permission) {
        long version = currentVersion(tenantId);
        String key = cacheKey(tenantId, version, userId);
        redis.opsForHash().put(key, FIELD_PERMS, join(permission.permCodes()));
        // 菜单树是结构化数据,序列化成 JSON 存一个字段;permCodes 仍是逗号串(它只是字符串集合)
        redis.opsForHash().put(key, FIELD_TREE, objectMapper.writeValueAsString(permission.menuTree()));
        redis.expire(key, Duration.ofSeconds(properties.cacheTtlSeconds()));
    }

    private String cacheKey(Long tenantId, long version, Long userId) {
        return CACHE_KEY_PREFIX + version + ":" + tenantId + ":" + userId;
    }

    private String join(Set<String> values) {
        return String.join(DELIMITER, values);
    }

    private Set<String> split(String value) {
        if (value == null || value.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(value.split(DELIMITER)));
    }

    /** 缓存内容:权限码与可见菜单树(前端生成动态路由用,见 7.4)。 */
    public record CachedPermission(Set<String> permCodes, List<MenuTreeNode> menuTree) {
    }
}
