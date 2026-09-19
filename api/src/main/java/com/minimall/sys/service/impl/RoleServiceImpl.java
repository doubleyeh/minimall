package com.minimall.sys.service.impl;

import com.minimall.sys.api.dto.MenuTreeNode;
import com.minimall.sys.api.dto.RoleCreateRequest;
import com.minimall.sys.api.dto.RoleMenuGrantRequest;
import com.minimall.sys.api.dto.RoleView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.sys.domain.QSysRole;
import com.minimall.sys.domain.SysDept;
import com.minimall.sys.domain.SysMenu;
import com.minimall.sys.domain.SysRole;
import com.minimall.sys.domain.Tenant;
import com.minimall.sys.domain.repository.SysDeptRepository;
import com.minimall.sys.domain.repository.SysMenuRepository;
import com.minimall.sys.domain.repository.SysPackageRepository;
import com.minimall.sys.domain.repository.SysRoleRepository;
import com.minimall.sys.domain.repository.TenantRepository;
import com.minimall.infra.security.PermissionCacheService;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.service.RoleService;
import com.minimall.sys.service.support.MenuTreeBuilder;
import com.querydsl.core.BooleanBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色与授权实现(架构文档 5.2.1、5.3、5.6)。
 *
 * <p><b>本类里最要紧的一段是 {@link #grantMenus}</b>:它把"可分配菜单 ⊆ 套餐菜单"这条约束
 * 落在接口上。少了它,4.8 的降级收回会被"管理员手动勾上的套餐外菜单"绕过 ——
 * 而那是最难发现的一类漏洞:功能测试全通过,只有安全测试会暴露。
 */
@Service
@Transactional
public class RoleServiceImpl implements RoleService {

    private static final int SCOPE_CUSTOM_DEPT = 4;

    private final SysRoleRepository roleRepository;
    private final SysMenuRepository menuRepository;
    private final SysDeptRepository deptRepository;
    private final SysPackageRepository packageRepository;
    private final TenantRepository tenantRepository;
    private final PermissionCacheService permissionCacheService;

    public RoleServiceImpl(SysRoleRepository roleRepository,
                           SysMenuRepository menuRepository,
                           SysDeptRepository deptRepository,
                           SysPackageRepository packageRepository,
                           TenantRepository tenantRepository,
                           PermissionCacheService permissionCacheService) {
        this.roleRepository = roleRepository;
        this.menuRepository = menuRepository;
        this.deptRepository = deptRepository;
        this.packageRepository = packageRepository;
        this.tenantRepository = tenantRepository;
        this.permissionCacheService = permissionCacheService;
    }

    @Override
    public PageResult<RoleView> page(String roleName, Integer status, int pageNo, int pageSize) {
        QSysRole qRole = QSysRole.sysRole;
        BooleanBuilder where = new BooleanBuilder();
        if (roleName != null && !roleName.isBlank()) {
            where.and(qRole.roleName.contains(roleName));
        }
        if (status != null) {
            where.and(qRole.status.eq(status));
        }
        // 租户隔离不在这里写:sys_role 走标准的 tenant_id 等值过滤(4.6.1),过滤器已经把它圈住了
        Page<SysRole> page = roleRepository.findAll(where, PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1)));
        List<RoleView> views = page.getContent().stream()
                .map(role -> new RoleView(role.getId(), role.getRoleKey(), role.getRoleName(), role.getDataScope(),
                        role.getIsDefault(), role.getStatus(), role.getCreateTime()))
                .toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public Long create(RoleCreateRequest request) {
        Long tenantId = requireTenantId();
        roleRepository.findByTenantIdAndRoleKey(tenantId, request.roleKey())
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.DATA_CONFLICT, "角色标识已存在");
                });
        validateDeptScope(request);

        SysRole role = new SysRole();
        role.setRoleKey(request.roleKey());
        role.setRoleName(request.roleName());
        role.setDataScope(request.dataScope());
        role.setIsDefault(0);
        role.setStatus(request.status() == null ? 1 : request.status());
        applyDeptIds(role, request);
        return roleRepository.save(role).getId();
    }

    @Override
    public void update(Long roleId, RoleCreateRequest request) {
        SysRole role = load(roleId);
        if (!role.getRoleKey().equals(request.roleKey())) {
            // role_key 是权限校验与外部系统引用的锚点,不允许改(改了等于换了一个角色)
            throw new BusinessException(ErrorCode.PARAM_INVALID, "角色标识不允许修改");
        }
        validateDeptScope(request);
        role.setRoleName(request.roleName());
        role.setDataScope(request.dataScope());
        if (request.status() != null) {
            role.setStatus(request.status());
        }
        applyDeptIds(role, request);
        // dataScope 变了,数据权限的可见范围就变了:虽然它不参与权限缓存(按请求实时计算,5.3),
        // 但为了不让"同一请求内前后不一致"的疑虑留下,这里不做额外处理,只记日志
        permissionCacheService.invalidateTenant(requireTenantId());
    }

    @Override
    public void delete(Long roleId) {
        SysRole role = load(roleId);
        if (role.isDefaultRole()) {
            // 默认管理员角色是套餐的投影,删了会让该租户失去管理入口(5.6)
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "默认管理员角色不允许删除");
        }
        long holders = roleRepository.countUsersOfRole(roleId);
        if (holders > 0) {
            // 不静默剥夺用户权限:先解绑再删(5.6)
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "该角色仍被 " + holders + " 个用户持有,请先解绑");
        }
        // sys_role_menu / sys_role_dept 是挂在角色上的 @ElementCollection,删角色时由 JPA 一并清理;
        // 但 sys_user_role 是挂在用户上的,反向关联不受管理,必须显式删
        roleRepository.deleteUserRolesByRoleId(roleId);
        roleRepository.delete(role);
        permissionCacheService.invalidateTenant(requireTenantId());
    }

    @Override
    public void changeStatus(Long roleId, int status) {
        load(roleId).setStatus(status);
        // 停用角色 = 该角色下所有用户的权限变小,必须失效缓存(5.5 的触发表)
        permissionCacheService.invalidateTenant(requireTenantId());
    }

    @Override
    public List<MenuTreeNode> grantableMenuTree(Long roleId) {
        load(roleId);
        return MenuTreeBuilder.build(grantableMenus());
    }

    @Override
    public List<Long> grantedMenuIds(Long roleId) {
        return List.copyOf(load(roleId).getMenuIds());
    }

    /**
     * 5.2.1 规则 2、3:保存授权前的二次校验。
     */
    @Override
    public void grantMenus(Long roleId, RoleMenuGrantRequest request) {
        SysRole role = load(roleId);
        if (role.isDefaultRole()) {
            // 默认角色的菜单由套餐同步维护,人工改过会被下次同步"修正",停在半对半错的状态(5.2.1 规则 3)
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "默认管理员角色的菜单由套餐维护,不支持人工调整");
        }

        Set<Long> allowed = grantableMenus().stream().map(SysMenu::getId).collect(java.util.stream.Collectors.toSet());
        Set<Long> requested = new LinkedHashSet<>(request.menuIds());
        Set<Long> outOfScope = difference(requested, allowed);
        if (!outOfScope.isEmpty()) {
            // 不做静默过滤:静默过滤会让"前端显示的授权"与"实际生效的授权"不一致(5.2.1)
            throw new BusinessException(ErrorCode.MENU_OUT_OF_PACKAGE,
                    "存在不在当前套餐范围内的菜单,授权已拒绝:" + outOfScope);
        }

        role.getMenuIds().clear();
        role.getMenuIds().addAll(requested);
        roleRepository.save(role);

        // 授权变了,该租户下一个请求就是新权限(5.5)
        permissionCacheService.invalidateTenant(requireTenantId());
    }

    /**
     * 当前租户**可分配**的菜单:套餐范围 ∩ 启用中的非平台菜单(5.2.1 规则 1)。
     *
     * <p>这是整个授权边界的地基:候选集与保存校验都必须用它,任何一处换成"全部菜单",
     * 降级收回就会被绕过。
     */
    private List<SysMenu> grantableMenus() {
        Long tenantId = requireTenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Set<Long> packageMenuIds = tenant.getPackageId() == null
                ? menuRepository.findAllNonPlatform().stream().map(SysMenu::getId).collect(java.util.stream.Collectors.toSet())
                : packageRepository.findById(tenant.getPackageId())
                        .map(pkg -> new LinkedHashSet<>(pkg.getMenuIds()))
                        .orElseGet(LinkedHashSet::new);

        return menuRepository.findByIdIn(packageMenuIds).stream()
                .filter(menu -> menu.getStatus() != null && menu.getStatus() == 1)
                .filter(menu -> !menu.isPlatformOnly())
                .toList();
    }

    private void validateDeptScope(RoleCreateRequest request) {
        if (request.dataScope() == null || request.dataScope() != SCOPE_CUSTOM_DEPT) {
            return;
        }
        if (request.deptIds() == null || request.deptIds().isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "数据权限为自定义部门时必须指定部门");
        }
        // 逐个校验部门存在(部门查询受租户过滤,所以别的租户的部门在这里查不到 → 天然挡住跨租户引用 4.6.1)
        for (Long deptId : request.deptIds()) {
            SysDept dept = deptRepository.findById(deptId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "部门不存在:" + deptId));
            if (!dept.getTenantId().equals(requireTenantId())) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "部门不存在:" + deptId);
            }
        }
    }

    private void applyDeptIds(SysRole role, RoleCreateRequest request) {
        role.getDeptIds().clear();
        if (request.dataScope() != null && request.dataScope() == SCOPE_CUSTOM_DEPT && request.deptIds() != null) {
            role.getDeptIds().addAll(request.deptIds());
        }
    }

    private SysRole load(Long roleId) {
        return roleRepository.findById(roleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            // 没有租户上下文时不允许做任何角色操作:猜一个租户等于把权限发给错误的租户
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return tenantId;
    }

    private Set<Long> difference(Set<Long> source, Set<Long> other) {
        Set<Long> result = new LinkedHashSet<>(source);
        result.removeAll(other);
        return result;
    }
}
