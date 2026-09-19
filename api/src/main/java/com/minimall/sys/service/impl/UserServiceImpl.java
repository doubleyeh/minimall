package com.minimall.sys.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.minimall.sys.api.dto.UserCreateResponse;
import com.minimall.sys.api.dto.UserResetPasswordResponse;
import com.minimall.sys.api.dto.UserSaveRequest;
import com.minimall.sys.api.dto.UserView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.Masking;
import com.minimall.common.PageResult;
import com.minimall.sys.domain.QSysUser;
import com.minimall.sys.domain.SysDept;
import com.minimall.sys.domain.SysRole;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.repository.SysDeptRepository;
import com.minimall.sys.domain.repository.SysRoleRepository;
import com.minimall.sys.domain.repository.SysUserRepository;
import com.minimall.infra.security.PermissionCacheService;
import com.minimall.infra.security.RefreshTokenService;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.service.UserService;
import com.minimall.sys.service.support.PasswordGenerator;
import com.querydsl.core.BooleanBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户管理实现(架构文档 5.3、5.6、7.1.2)。
 *
 * <p>这一层**不需要手写部门范围条件**:{@code sys_user} 是参与数据权限过滤的表(4.6.1),
 * 列表与详情查询都会被 {@code dataScopeFilter} 按当前用户的角色自动收窄。
 * 在这里再写一遍部门条件,反而会出现"两套范围规则打架"的问题。
 *
 * <p>按 ID 的读/改/删都走"经过滤查询加载实体"的方式(7.3 的越权防护),
 * 加载不到就是统一的"资源不存在",不用 bulk 语句直接改。
 */
@Service
@Transactional
public class UserServiceImpl implements UserService {

    private final SysUserRepository userRepository;
    private final SysDeptRepository deptRepository;
    private final SysRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionCacheService permissionCacheService;
    private final RefreshTokenService refreshTokenService;

    public UserServiceImpl(SysUserRepository userRepository,
                           SysDeptRepository deptRepository,
                           SysRoleRepository roleRepository,
                           PasswordEncoder passwordEncoder,
                           PermissionCacheService permissionCacheService,
                           RefreshTokenService refreshTokenService) {
        this.userRepository = userRepository;
        this.deptRepository = deptRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.permissionCacheService = permissionCacheService;
        this.refreshTokenService = refreshTokenService;
    }

    @Override
    public PageResult<UserView> page(String username, Long deptId, Integer status, int pageNo, int pageSize) {
        QSysUser qUser = QSysUser.sysUser;
        BooleanBuilder where = new BooleanBuilder();
        if (username != null && !username.isBlank()) {
            where.and(qUser.username.contains(username));
        }
        if (deptId != null) {
            // 这是"业务筛选条件",与数据权限的部门范围是"与"的关系(5.3)
            where.and(qUser.deptId.eq(deptId));
        }
        if (status != null) {
            where.and(qUser.status.eq(status));
        }
        Page<SysUser> page = userRepository.findAll(where, PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1)));

        Map<Long, String> deptNames = deptNames(page.getContent().stream().map(SysUser::getDeptId).toList());
        List<UserView> views = page.getContent().stream().map(user -> toView(user, deptNames)).toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public UserView detail(Long userId) {
        SysUser user = load(userId);
        return toView(user, deptNames(List.of(user.getDeptId() == null ? 0L : user.getDeptId())));
    }

    @Override
    public UserCreateResponse create(UserSaveRequest request) {
        Long tenantId = requireTenantId();
        userRepository.findByTenantIdAndUsername(tenantId, request.username()).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "用户名已存在");
        });
        validateDept(request.deptId());
        validateRoles(request.roleIds());

        boolean generated = request.password() == null || request.password().isBlank();
        String rawPassword = generated ? PasswordGenerator.generate() : request.password();

        SysUser user = new SysUser();
        user.setUsername(request.username());
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setNickname(request.nickname());
        user.setPhone(request.phone());
        user.setDeptId(request.deptId());
        user.setStatus(request.status() == null ? 1 : request.status());
        user.setIsSuper(0);
        user.setMustChangePassword(generated ? 1 : 0);
        user.setLoginFailCount(0);
        if (request.roleIds() != null) {
            user.getRoleIds().addAll(request.roleIds());
        }
        userRepository.save(user);

        // 明文只返回一次(7.1.2)
        return new UserCreateResponse(user.getId(), user.getUsername(), generated ? rawPassword : null, generated);
    }

    @Override
    public void update(Long userId, UserSaveRequest request) {
        SysUser user = load(userId);
        // username 不允许改:它可能已被审计日志、外部系统引用(UserSaveRequest 的说明);password 走改密/重置接口
        validateDept(request.deptId());
        validateRoles(request.roleIds());

        user.setNickname(request.nickname());
        user.setPhone(request.phone());
        user.setDeptId(request.deptId());
        if (request.status() != null) {
            user.setStatus(request.status());
        }
        if (request.roleIds() != null) {
            Set<Long> newRoles = new LinkedHashSet<>(request.roleIds());
            boolean changed = !newRoles.equals(new LinkedHashSet<>(user.getRoleIds()));
            user.getRoleIds().clear();
            user.getRoleIds().addAll(newRoles);
            if (changed) {
                // 角色变了 → 权限集合变了 → 必须失效缓存(5.5 的触发表)
                permissionCacheService.invalidateTenant(requireTenantId());
            }
        }
    }

    @Override
    public void delete(Long userId) {
        SysUser user = load(userId);
        Long currentUserId = currentUserId();
        if (Objects.equals(user.getId(), currentUserId)) {
            throw new BusinessException(ErrorCode.BUSINESS_ERROR, "不能删除当前登录用户");
        }
        // 不能删掉该租户最后一个持有默认管理员角色的人,否则该租户失去管理入口(5.6)
        roleRepository.findDefaultRole(requireTenantId()).ifPresent(defaultRole -> {
            if (user.getRoleIds().contains(defaultRole.getId())
                    && roleRepository.countUsersOfRole(defaultRole.getId()) <= 1) {
                throw new BusinessException(ErrorCode.BUSINESS_ERROR, "不能删除该租户最后一个管理员");
            }
        });
        // sys_user_role 是挂在用户上的 @ElementCollection,删用户时由 JPA 一并清理
        userRepository.delete(user);
        // 用户没了,权限缓存与刷新令牌都不该留着
        refreshTokenService.revokeAll(userId);
        permissionCacheService.invalidateTenant(requireTenantId());
    }

    @Override
    public void changeStatus(Long userId, int status) {
        SysUser user = load(userId);
        user.setStatus(status);
        permissionCacheService.invalidateTenant(requireTenantId());
        if (status == 0) {
            // 禁用是一个明确的动作,这里**主动**踢下线并撤销刷新令牌(7.1.2、7.1.3),
            // 不依赖"下次请求时惰性发现"
            refreshTokenService.revokeAll(userId);
            StpUtil.logout(userId);
        }
    }

    @Override
    public UserResetPasswordResponse resetPassword(Long userId) {
        SysUser user = load(userId);
        String rawPassword = PasswordGenerator.generate();
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setMustChangePassword(1);
        user.setPwdUpdateTime(java.time.LocalDateTime.now());
        user.setLoginFailCount(0);
        user.setLockTime(null);

        // 密码变了,所有凭据都失效(7.1.2):撤销刷新令牌 + 踢下线
        refreshTokenService.revokeAll(userId);
        StpUtil.logout(userId);

        return new UserResetPasswordResponse(user.getId(), user.getUsername(), rawPassword, true);
    }

    @Override
    public void unlock(Long userId) {
        SysUser user = load(userId);
        user.setLoginFailCount(0);
        user.setLockTime(null);
    }

    private UserView toView(SysUser user, Map<Long, String> deptNames) {
        return new UserView(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                // 服务端脱敏,不把明文交给前端(7.1)
                Masking.maskPhone(user.getPhone()),
                user.getDeptId(),
                user.getDeptId() == null ? null : deptNames.get(user.getDeptId()),
                user.getStatus(),
                user.getLockTime(),
                user.isMustChangePassword(),
                user.getCreateTime());
    }

    private Map<Long, String> deptNames(Collection<Long> deptIds) {
        Set<Long> ids = deptIds.stream().filter(Objects::nonNull).filter(id -> id != 0L).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return deptRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(SysDept::getId, SysDept::getDeptName, (a, b) -> a));
    }

    private void validateDept(Long deptId) {
        if (deptId == null) {
            return;
        }
        // 部门查询受租户过滤,别租户的部门查不到 → 天然挡住跨租户归属(4.6.1)
        deptRepository.findById(deptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "部门不存在"));
    }

    private void validateRoles(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        List<SysRole> roles = roleRepository.findByIdInAndTenantId(roleIds, requireTenantId());
        if (roles.size() != new LinkedHashSet<>(roleIds).size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "存在不属于当前租户的角色");
        }
    }

    private SysUser load(Long userId) {
        // 经过滤查询加载:不可见(别的租户/不在数据范围内)时统一当作"资源不存在"(7.3)
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private Long currentUserId() {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        return loginId == null ? null : Long.valueOf(String.valueOf(loginId));
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return tenantId;
    }
}
