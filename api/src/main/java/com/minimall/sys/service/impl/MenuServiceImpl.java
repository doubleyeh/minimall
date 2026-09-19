package com.minimall.sys.service.impl;

import com.minimall.sys.api.dto.MenuSaveRequest;
import com.minimall.sys.api.dto.MenuTreeNode;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.sys.domain.SysMenu;
import com.minimall.sys.domain.Tenant;
import com.minimall.sys.domain.repository.SysMenuRepository;
import com.minimall.sys.domain.repository.SysRoleRepository;
import com.minimall.sys.domain.repository.SysPackageRepository;
import com.minimall.sys.domain.repository.TenantRepository;
import com.minimall.infra.security.PermissionCacheService;
import com.minimall.sys.service.MenuService;
import com.minimall.sys.service.support.MenuTreeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 菜单维护实现(架构文档 5.1、5.2、5.6)。
 *
 * <p>菜单是**平台级数据**,影响所有租户,所以这里的每一条写操作都要考虑"要不要让全部租户的权限缓存失效":
 * 改 perm_code、禁用、删除都必须失效(5.5 的触发表里写明"全部租户")。
 * 只失效"当前租户"是不够的 —— 菜单不属于任何租户。
 */
@Service
@Transactional
public class MenuServiceImpl implements MenuService {

    private static final Logger log = LoggerFactory.getLogger(MenuServiceImpl.class);

    private final SysMenuRepository menuRepository;
    private final SysRoleRepository roleRepository;
    private final SysPackageRepository packageRepository;
    private final TenantRepository tenantRepository;
    private final PermissionCacheService permissionCacheService;

    public MenuServiceImpl(SysMenuRepository menuRepository,
                           SysRoleRepository roleRepository,
                           SysPackageRepository packageRepository,
                           TenantRepository tenantRepository,
                           PermissionCacheService permissionCacheService) {
        this.menuRepository = menuRepository;
        this.roleRepository = roleRepository;
        this.packageRepository = packageRepository;
        this.tenantRepository = tenantRepository;
        this.permissionCacheService = permissionCacheService;
    }

    @Override
    public List<MenuTreeNode> tree(Integer status, Integer menuType) {
        List<SysMenu> menus = menuRepository.findAll().stream()
                .filter(menu -> status == null || status.equals(menu.getStatus()))
                .filter(menu -> menuType == null || menuType.equals(menu.getMenuType()))
                .toList();
        return MenuTreeBuilder.build(menus);
    }

    @Override
    public Long create(MenuSaveRequest request) {
        validate(request, null);
        SysMenu menu = new SysMenu();
        apply(menu, request);
        SysMenu saved = menuRepository.save(menu);
        invalidateAllTenants("新增菜单");
        return saved.getId();
    }

    @Override
    public void update(Long menuId, MenuSaveRequest request) {
        SysMenu menu = load(menuId);
        validate(request, menuId);
        boolean permCodeChanged = !Objects.equals(menu.getPermCode(), emptyToNull(request.permCode()));
        apply(menu, request);
        if (permCodeChanged) {
            // 权限码变了却不让缓存失效,等于"老权限还能继续用"(5.5)
            invalidateAllTenants("修改菜单权限标识");
        }
    }

    @Override
    public void delete(Long menuId) {
        SysMenu menu = load(menuId);
        if (!menuRepository.findByParentId(menuId).isEmpty()) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "存在子菜单,请先删除子菜单");
        }
        // 5.6:被套餐或角色引用时**允许删除**,但必须在同一事务里级联清理这两张关联表,
        // 否则会留下指向不存在菜单的授权记录(孤儿数据,靠人查是查不出来的)
        int roleRefs = roleRepository.deleteRoleMenusByMenuId(menuId);
        int packageRefs = packageRepository.deletePackageMenusByMenuId(menuId);
        menuRepository.delete(menu);
        invalidateAllTenants("删除菜单");
        log.info("菜单已删除 menuId={} 清理角色授权={} 处 清理套餐引用={} 处", menuId, roleRefs, packageRefs);
    }

    /**
     * 让**全部租户**的权限缓存失效。菜单是平台级数据,改动影响所有租户(5.5)。
     */
    private void invalidateAllTenants(String reason) {
        List<Tenant> tenants = tenantRepository.findAll();
        for (Tenant tenant : tenants) {
            permissionCacheService.invalidateTenant(tenant.getId());
        }
        log.info("已失效全部租户权限缓存 原因={} 租户数={}", reason, tenants.size());
    }

    private void apply(SysMenu menu, MenuSaveRequest request) {
        menu.setParentId(request.parentId() == null ? 0L : request.parentId());
        menu.setMenuName(request.menuName());
        menu.setMenuType(request.menuType());
        menu.setRoutePath(request.routePath());
        menu.setPermCode(emptyToNull(request.permCode()));
        menu.setIcon(request.icon());
        menu.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        menu.setStatus(request.status() == null ? 1 : request.status());
        // is_platform 是"提高防线"的开关(4.10):平台管理类菜单必须显式标 1,标了就不允许进任何套餐
        menu.setIsPlatform(Boolean.TRUE.equals(request.isPlatform()) ? 1 : 0);
    }

    /**
     * 校验:按钮必须有权限码、权限码全局唯一、上级菜单合法(不能挂到自己或自己的子孙下)。
     *
     * @param selfId 修改时的自身 ID;新增传 null
     */
    private void validate(MenuSaveRequest request, Long selfId) {
        Integer menuType = request.menuType();
        if (menuType != null && menuType == SysMenu.TYPE_BUTTON
                && (request.permCode() == null || request.permCode().isBlank())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "按钮级菜单必须填写权限标识");
        }

        String permCode = emptyToNull(request.permCode());
        if (permCode != null) {
            menuRepository.findByPermCode(permCode).ifPresent(existing -> {
                if (!existing.getId().equals(selfId)) {
                    // 权限码不唯一会让 @SaCheckPermission 的语义失效:授权其中一个等于顺带授权另一个(5.2)
                    throw new BusinessException(ErrorCode.DATA_CONFLICT, "权限标识已被其他菜单使用:" + permCode);
                }
            });
        }

        Long parentId = request.parentId();
        if (parentId == null || parentId == 0L) {
            return;
        }
        SysMenu parent = menuRepository.findById(parentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "上级菜单不存在"));
        if (selfId == null) {
            return;
        }
        if (parent.getId().equals(selfId) || isDescendant(parent.getId(), selfId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "上级菜单不能是自己或自己的下级");
        }
    }

    /**
     * 判断 {@code candidateId} 是否落在 {@code ancestorId} 的子树里。
     *
     * <p>{@code sys_menu} 没有 {@code ancestors} 冗余列(和部门不同),所以这里在内存里回溯父链。
     * 菜单数量级很小(几十到几百),这样最简单可靠。
     */
    private boolean isDescendant(Long candidateId, Long ancestorId) {
        Map<Long, Long> parentOf = new HashMap<>();
        for (SysMenu menu : menuRepository.findAll()) {
            parentOf.put(menu.getId(), menu.getParentId());
        }
        Set<Long> visited = new HashSet<>();
        Long current = candidateId;
        while (current != null && current != 0L && visited.add(current)) {
            Long parent = parentOf.get(current);
            if (parent == null) {
                break;
            }
            if (parent.equals(ancestorId)) {
                return true;
            }
            current = parent;
        }
        return false;
    }

    private SysMenu load(Long menuId) {
        return menuRepository.findById(menuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
