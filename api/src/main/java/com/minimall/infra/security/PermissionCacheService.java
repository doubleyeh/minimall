package com.minimall.infra.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 权限**事实**的缓存(架构文档 5.5)。
 *
 * <p>只缓存两类不常变、且能按 id 精确定位的事实:启用中的菜单 ID、某个套餐包含的菜单 ID。
 * **不缓存"某个用户最终有什么权限"** —— 那个结果由这些事实在读取时交算得出(见
 * {@code PermissionProviderImpl}),所以角色授权变更、用户角色变更、用户禁用都不需要任何失效动作。
 *
 * <p>失效因此是精确的:菜单增删/启停只删 {@code perm:menus};套餐菜单变化只删那一个
 * {@code perm:pkg:{packageId}}。不存在"改一个角色把整个租户的用户缓存一起废掉"这种连带。
 */
@Component
public class PermissionCacheService {

    private static final String MENUS_KEY = "perm:menus";
    private static final String PACKAGE_KEY_PREFIX = "perm:pkg:";

    private final StringRedisTemplate redis;
    private final PermissionProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PermissionCacheService(StringRedisTemplate redis, PermissionProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 启用中的菜单 ID;未命中返回空。 */
    public Optional<List<Long>> enabledMenuIds() {
        return readIds(MENUS_KEY);
    }

    public void putEnabledMenuIds(Collection<Long> ids) {
        writeIds(MENUS_KEY, ids);
    }

    /** 套餐包含的菜单 ID;未命中返回空。 */
    public Optional<List<Long>> packageMenuIds(Long packageId) {
        return readIds(PACKAGE_KEY_PREFIX + packageId);
    }

    public void putPackageMenuIds(Long packageId, Collection<Long> ids) {
        writeIds(PACKAGE_KEY_PREFIX + packageId, ids);
    }

    /** 菜单被新增/修改/删除/启停后调用。 */
    public void evictMenus() {
        redis.delete(MENUS_KEY);
    }

    /** 套餐包含的菜单变化后调用。 */
    public void evictPackage(Long packageId) {
        if (packageId != null) {
            redis.delete(PACKAGE_KEY_PREFIX + packageId);
        }
    }

    private Optional<List<Long>> readIds(String key) {
        String raw = redis.opsForValue().get(key);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(raw, new TypeReference<List<Long>>() {
            }));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private void writeIds(String key, Collection<Long> ids) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(ids),
                    Duration.ofSeconds(properties.cacheTtlSeconds()));
        } catch (RuntimeException ex) {
            // 缓存写失败不影响正确性,下一次读取会重新查库
        }
    }
}
