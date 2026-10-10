package com.minimall.sys.service.support;

import com.minimall.infra.security.PermissionCacheService;
import com.minimall.infra.security.PermissionProvider;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.api.dto.MenuTreeNode;
import com.minimall.sys.domain.SysMenu;
import com.minimall.sys.domain.SysRole;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.repository.SysMenuRepository;
import com.minimall.sys.domain.repository.SysPackageRepository;
import com.minimall.sys.domain.repository.SysRoleRepository;
import com.minimall.sys.domain.repository.SysUserRepository;
import com.minimall.sys.domain.repository.TenantRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限集合的计算(架构文档 5.2、5.5、4.10)。
 *
 * <p><b>权限在读取时交算,不做"每用户一份算好的缓存"</b>:
 * {@code permCodes = 角色授权的菜单 ∩ 套餐包含的菜单 ∩ 启用中的菜单}。后两份输入按 id 缓存
 * (见 {@link PermissionCacheService}),角色菜单随角色一起读(本来就要读角色判状态)。
 *
 * <p>为什么不再缓存"用户 → 权限"的结果:那份结果的失效粒度是租户级的(改一个角色要废掉整个租户
 * 所有用户的缓存),而用户数远多于角色数与套餐数,粒度和变更成本是反着的。改成按事实缓存后,
 * 角色授权变更、用户角色变更、用户禁用**都不需要任何失效动作**,权限下一次读取即生效。
 *
 * <p><b>超管短路</b>(4.10):{@code sys_user.is_super = 1} 直接返回全量启用菜单的 perm_code,
 * 不走角色也不受套餐约束。走角色计算只会引入"平台租户的角色被误改导致超管进不了后台"这类运维事故。
 */
@Service
public class PermissionProviderImpl implements PermissionProvider {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysMenuRepository menuRepository;
    private final SysPackageRepository packageRepository;
    private final TenantRepository tenantRepository;
    private final PermissionCacheService permissionCacheService;

    public PermissionProviderImpl(SysUserRepository userRepository,
                                  SysRoleRepository roleRepository,
                                  SysMenuRepository menuRepository,
                                  SysPackageRepository packageRepository,
                                  TenantRepository tenantRepository,
                                  PermissionCacheService permissionCacheService) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.menuRepository = menuRepository;
        this.packageRepository = packageRepository;
        this.tenantRepository = tenantRepository;
        this.permissionCacheService = permissionCacheService;
    }

    @Override
    public PermissionData load(Long userId) {
        if (userId == null) {
            return PermissionData.empty();
        }
        if (TenantContext.getTenantId() == null) {
            // 说明调用发生在请求/任务上下文之外,直接按"无权限"处理 —— 默认拒绝,
            // 不要去猜一个租户(猜错就是把别人的权限给了当前调用者)
            return PermissionData.empty();
        }
        return compute(userId);
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

        Set<Long> menuIds = new HashSet<>(collectMenuIds(user));
        if (menuIds.isEmpty()) {
            return PermissionData.empty();
        }
        menuIds.retainAll(enabledMenuIds());
        // 套餐是上限:角色能授权的菜单本就是套餐的子集,这里再兜一层,于是平台改套餐
        // 不需要回写任何租户的角色菜单(4.8.1)
        Set<Long> packageMenus = packageMenuIds(user.getTenantId());
        if (packageMenus != null) {
            menuIds.retainAll(packageMenus);
        }
        if (menuIds.isEmpty()) {
            return PermissionData.empty();
        }

        List<SysMenu> menus = menuRepository.findByIdIn(menuIds).stream()
                .filter(menu -> menu.getStatus() != null && menu.getStatus() == 1)
                // 平台专用菜单只属于平台租户(4.10)
                .filter(menu -> !menu.isPlatformOnly())
                .toList();
        return toPermissionData(menus);
    }

    /** 启用中的菜单 ID(缓存未命中时查库并回填)。 */
    private Set<Long> enabledMenuIds() {
        return permissionCacheService.enabledMenuIds()
                .map(HashSet::new)
                .orElseGet(() -> {
                    List<Long> ids = menuRepository.findAllEnabled().stream()
                            .map(SysMenu::getId)
                            .toList();
                    permissionCacheService.putEnabledMenuIds(ids);
                    return new HashSet<>(ids);
                });
    }

    /** 该租户当前套餐包含的菜单 ID;套餐"不限"时返回 null,表示不施加套餐约束。 */
    private Set<Long> packageMenuIds(Long tenantId) {
        Long packageId = tenantRepository.findById(tenantId)
                .map(tenant -> tenant.getPackageId())
                .orElse(null);
        if (packageId == null) {
            return null;
        }
        return permissionCacheService.packageMenuIds(packageId)
                .map(HashSet::new)
                .orElseGet(() -> {
                    HashSet<Long> ids = new HashSet<>();
                    ids.addAll(packageRepository.findMenuIdsOfPackage(packageId));
                    permissionCacheService.putPackageMenuIds(packageId, ids);
                    return ids;
                });
    }

    /** 用户所有**有效角色**的菜单并集。停用角色不参与计算。 */
    private Set<Long> collectMenuIds(SysUser user) {
        List<Long> roleIds = roleRepository.findRoleIdsOfUser(user.getId());
        if (roleIds == null || roleIds.isEmpty()) {
            return Set.of();
        }
        Set<Long> menuIds = new HashSet<>();
        for (SysRole role : roleRepository.findByIdInAndTenantId(roleIds, user.getTenantId())) {
            if (role.isEnabled()) {
                menuIds.addAll(roleRepository.findMenuIdsOfRole(role.getId()));
            }
        }
        return menuIds;
    }

    /**
     * @param menus 菜单集合
     * @return permCodes = 全部非空 perm_code(按钮的也要,与是否进导航无关);menuTree = 导航菜单树
     */
    private PermissionData toPermissionData(List<SysMenu> menus) {
        Set<String> permCodes = new LinkedHashSet<>();
        for (SysMenu menu : menus) {
            if (menu.getPermCode() != null && !menu.getPermCode().isBlank()) {
                permCodes.add(menu.getPermCode());
            }
        }
        List<MenuTreeNode> tree = pruneEmptyDirectories(MenuTreeBuilder.build(navigationMenus(menus)));
        return new PermissionData(permCodes, tree);
    }

    /**
     * 导航候选:非按钮、启用、有 route_path、且父链完整。
     *
     * <p>这里刻意不用 {@link MenuTreeBuilder} 的"孤儿提升为根"行为:那是给菜单管理/套餐/角色三棵树用的
     * (让管理员发现父级缺失),导航侧要的相反 —— 父目录没授权就不该冒出一个孤零零的顶层页面。
     */
    private List<SysMenu> navigationMenus(List<SysMenu> menus) {
        Map<Long, SysMenu> byId = new HashMap<>();
        for (SysMenu menu : menus) {
            byId.put(menu.getId(), menu);
        }
        return menus.stream()
                .filter(menu -> !menu.isButton())
                .filter(menu -> menu.getStatus() != null && menu.getStatus() == 1)
                .filter(menu -> menu.getRoutePath() != null && !menu.getRoutePath().isBlank())
                .filter(menu -> ancestorsPresent(menu, byId))
                .toList();
    }

    /** 父链上每一级都必须也在候选集合里,否则这个节点不该进导航。 */
    private boolean ancestorsPresent(SysMenu menu, Map<Long, SysMenu> byId) {
        Set<Long> seen = new HashSet<>();
        Long parentId = menu.getParentId();
        while (parentId != null && parentId != 0L) {
            if (!seen.add(parentId)) {
                return false;
            }
            SysMenu parent = byId.get(parentId);
            if (parent == null || parent.isButton()
                    || parent.getStatus() == null || parent.getStatus() != 1
                    || parent.getRoutePath() == null || parent.getRoutePath().isBlank()) {
                return false;
            }
            parentId = parent.getParentId();
        }
        return true;
    }

    /** 目录下没有任何页面子孙时一并去掉,避免前端出现点不开的空目录。 */
    private List<MenuTreeNode> pruneEmptyDirectories(List<MenuTreeNode> nodes) {
        List<MenuTreeNode> kept = new ArrayList<>();
        for (MenuTreeNode node : nodes) {
            List<MenuTreeNode> children = pruneEmptyDirectories(node.children());
            if (node.menuType() != null && node.menuType() == 1 && children.isEmpty()) {
                continue;
            }
            kept.add(new MenuTreeNode(node.id(), node.parentId(), node.menuName(), node.menuType(),
                    node.routePath(), node.icon(), node.permCode(), node.sortOrder(), node.status(), children));
        }
        return kept;
    }
}
