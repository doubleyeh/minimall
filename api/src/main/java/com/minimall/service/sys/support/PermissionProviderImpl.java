package com.minimall.service.sys.support;

import com.minimall.domain.sys.SysMenu;
import com.minimall.domain.sys.SysRole;
import com.minimall.domain.sys.SysUser;
import com.minimall.domain.sys.repository.SysMenuRepository;
import com.minimall.domain.sys.repository.SysRoleRepository;
import com.minimall.domain.sys.repository.SysUserRepository;
import com.minimall.infra.security.PermissionCacheService;
import com.minimall.infra.security.PermissionProvider;
import com.minimall.infra.tenant.TenantContext;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 权限集合的计算与缓存(架构文档 5.2、5.5、4.10)。
 *
 * <p>计算规则:用户 → 有效角色(status = 1) → 这些角色的菜单并集 → 菜单的 perm_code 并集。
 * 菜单权限本身是**并集**语义(与数据权限取最大不同,因为两者一个决定"能不能做"、
 * 一个决定"能看到多少数据",见 5.2)。
 *
 * <p><b>超管短路</b>(4.10):{@code sys_user.is_super = 1} 直接返回全量启用菜单的 perm_code,
 * 不查角色。走角色计算只会引入"平台租户的角色被误改导致超管进不了后台"这类运维事故。
 *
 * <p>缓存按 5.5 的版本号方案读写;版本号一变,这里读到的就是新 key(旧的靠 TTL 自然过期)。
 */
@Service
public class PermissionProviderImpl implements PermissionProvider {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysMenuRepository menuRepository;
    private final PermissionCacheService permissionCacheService;

    public PermissionProviderImpl(SysUserRepository userRepository,
                                  SysRoleRepository roleRepository,
                                  SysMenuRepository menuRepository,
                                  PermissionCacheService permissionCacheService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.menuRepository = menuRepository;
        this.permissionCacheService = permissionCacheService;
    }

    @Override
    public PermissionData load(Long userId) {
        if (userId == null) {
            return PermissionData.empty();
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            // 缓存 key 里带租户,没有租户就不能安全读写缓存。
            // 这种情况说明调用发生在请求/任务上下文之外,直接按"无权限"处理 —— 默认拒绝,
            // 不要去猜一个租户(猜错就是把别人的权限给了当前调用者)。
            return PermissionData.empty();
        }

        Optional<PermissionCacheService.CachedPermission> cached = permissionCacheService.get(tenantId, userId);
        if (cached.isPresent()) {
            return new PermissionData(cached.get().permCodes(), cached.get().menus());
        }

        PermissionData computed = compute(userId);
        permissionCacheService.put(tenantId, userId,
                new PermissionCacheService.CachedPermission(computed.permCodes(), computed.menus()));
        return computed;
    }

    private PermissionData compute(Long userId) {
        SysUser user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isEnabled()) {
            // 停用用户的权限按空处理:即使会话还在,也做不了任何需要权限的事
            return PermissionData.empty();
        }
        if (user.isSuperUser()) {
            return toPermissionData(menuRepository.findAllEnabled());
        }

        Set<Long> menuIds = collectMenuIds(user);
        if (menuIds.isEmpty()) {
            return PermissionData.empty();
        }
        List<SysMenu> menus = menuRepository.findByIdIn(menuIds).stream()
                .filter(menu -> menu.getStatus() != null && menu.getStatus() == 1)
                .toList();
        return toPermissionData(menus);
    }

    /** 用户所有**有效角色**的菜单并集。停用角色不参与计算(5.5 的触发表里角色停用也要失效缓存)。 */
    private Set<Long> collectMenuIds(SysUser user) {
        Collection<Long> roleIds = user.getRoleIds();
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> menuIds = new HashSet<>();
        for (SysRole role : roleRepository.findByIdInAndTenantId(roleIds, user.getTenantId())) {
            if (role.isEnabled()) {
                menuIds.addAll(role.getMenuIds());
            }
        }
        return menuIds;
    }

    /**
     * @param menus 菜单集合
     * @return permCodes = 全部非空 perm_code;menus = 非按钮菜单的 route_path(前端渲染动态路由用)
     */
    private PermissionData toPermissionData(List<SysMenu> menus) {
        Set<String> permCodes = new LinkedHashSet<>();
        Set<String> routes = new LinkedHashSet<>();
        for (SysMenu menu : menus) {
            if (menu.getPermCode() != null && !menu.getPermCode().isBlank()) {
                permCodes.add(menu.getPermCode());
            }
            if (!menu.isButton() && menu.getRoutePath() != null && !menu.getRoutePath().isBlank()) {
                routes.add(menu.getRoutePath());
            }
        }
        return new PermissionData(permCodes, routes);
    }
}
