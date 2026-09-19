package com.minimall.sys.service.impl;

import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import com.minimall.sys.api.dto.TenantPackageChangeRequest;
import com.minimall.sys.api.dto.TenantView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.sys.domain.QTenant;
import com.minimall.sys.domain.SysDept;
import com.minimall.sys.domain.SysPackage;
import com.minimall.sys.domain.SysRole;
import com.minimall.sys.domain.SysTenantPackageChange;
import com.minimall.sys.domain.SysUser;
import com.minimall.sys.domain.Tenant;
import com.minimall.sys.domain.repository.SysDeptRepository;
import com.minimall.sys.domain.repository.SysMenuRepository;
import com.minimall.sys.domain.repository.SysPackageRepository;
import com.minimall.sys.domain.repository.SysRoleRepository;
import com.minimall.sys.domain.repository.SysTenantPackageChangeRepository;
import com.minimall.sys.domain.repository.SysUserRepository;
import com.minimall.sys.domain.repository.TenantRepository;
import com.minimall.infra.security.PermissionCacheService;
import com.minimall.infra.security.RefreshTokenService;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.infra.tenant.TenantFilterService;
import com.minimall.infra.tenant.TenantLookup;
import com.minimall.infra.tenant.TenantSnapshot;
import com.minimall.sys.service.TenantService;
import com.minimall.sys.service.support.PasswordGenerator;
import com.querydsl.core.BooleanBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 租户管理实现(架构文档 4.7、4.8、4.11、5.6)。
 *
 * <p>这里有两个"不能省"的动作,漏掉任何一个都会静默出错:
 * <ul>
 *   <li>建租户的子步骤必须在**新租户的上下文**里做({@code runAsTenant}):{@code tenant_id} 靠上下文回填(4.5),
 *       而且上下文变了之后还要**重新 apply 过滤器**,否则后续按新租户查什么都查不到</li>
 *   <li>套餐变更后必须 INCR 该租户的权限版本号(5.5):不收权就是"降级没生效"</li>
 * </ul>
 */
@Service
@Transactional
public class TenantServiceImpl implements TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantServiceImpl.class);
    // 初始密码的生成统一走 PasswordGenerator(建租户、新增用户、重置密码三处必须一致,7.1.2)

    private final TenantRepository tenantRepository;
    private final SysPackageRepository packageRepository;
    private final SysRoleRepository roleRepository;
    private final SysUserRepository userRepository;
    private final SysDeptRepository deptRepository;
    private final SysMenuRepository menuRepository;
    private final SysTenantPackageChangeRepository changeRepository;
    private final PasswordEncoder passwordEncoder;
    private final TenantLookup tenantLookup;
    private final PermissionCacheService permissionCacheService;
    private final RefreshTokenService refreshTokenService;
    private final TenantFilterService tenantFilterService;

    @PersistenceContext
    private EntityManager entityManager;

    public TenantServiceImpl(TenantRepository tenantRepository,
                             SysPackageRepository packageRepository,
                             SysRoleRepository roleRepository,
                             SysUserRepository userRepository,
                             SysDeptRepository deptRepository,
                             SysMenuRepository menuRepository,
                             SysTenantPackageChangeRepository changeRepository,
                             PasswordEncoder passwordEncoder,
                             TenantLookup tenantLookup,
                             PermissionCacheService permissionCacheService,
                             RefreshTokenService refreshTokenService,
                             TenantFilterService tenantFilterService) {
        this.tenantRepository = tenantRepository;
        this.packageRepository = packageRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.deptRepository = deptRepository;
        this.menuRepository = menuRepository;
        this.changeRepository = changeRepository;
        this.passwordEncoder = passwordEncoder;
        this.tenantLookup = tenantLookup;
        this.permissionCacheService = permissionCacheService;
        this.refreshTokenService = refreshTokenService;
        this.tenantFilterService = tenantFilterService;
    }

    @Override
    public TenantCreateResponse create(TenantCreateRequest request) {
        if (tenantRepository.findByTenantCode(request.tenantCode()).isPresent()) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "租户编码已存在");
        }
        // 套餐必填:不接受创建为"不限"(4.8 步骤 1),否则往后每次变更都要处理一次全量差异
        SysPackage tenantPackage = packageRepository.findById(request.packageId())
                .filter(pkg -> pkg.getStatus() != null && pkg.getStatus() == 1)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_ERROR, "套餐不存在或已禁用"));

        Tenant tenant = new Tenant();
        tenant.setTenantCode(request.tenantCode());
        tenant.setTenantName(request.tenantName());
        tenant.setStatus(1);
        tenant.setPackageId(tenantPackage.getId());
        tenant.setExpireTime(request.expireTime());
        tenantRepository.save(tenant);

        return createTenantAssets(tenant, tenantPackage, request);
    }

    /**
     * 4.7 的第 2~6 步:默认管理员角色(带套餐菜单) → 根部门 → 管理员用户。整个事务由外层保证。
     */
    private TenantCreateResponse createTenantAssets(Tenant tenant, SysPackage tenantPackage, TenantCreateRequest request) {
        return TenantContext.callAsTenant(tenant.getId(), false, () -> {
            // 上下文刚切换,过滤器还绑着"上一个"租户(或未定租户的哨兵),必须重新应用
            tenantFilterService.apply(entityManager);

            // 第 2 步:默认管理员角色。is_default = 1 是套餐同步的锚点(4.8 只自动同步这个角色)
            SysRole role = new SysRole();
            role.setRoleKey("admin");
            role.setRoleName("租户管理员");
            role.setDataScope(5);
            role.setIsDefault(1);
            role.setStatus(1);
            role.getMenuIds().addAll(tenantPackage.getMenuIds());
            roleRepository.save(role);

            // 第 3 步:根部门。不能省 —— data_scope = 2/3/4 全依赖部门层级,没有根部门这些档位会退化成"看不到任何数据"
            SysDept dept = new SysDept();
            dept.setParentId(0L);
            dept.setAncestors("");
            dept.setDeptName(request.tenantName() + "总部");
            dept.setSortOrder(1);
            dept.setStatus(1);
            deptRepository.save(dept);

            // 第 4 步:管理员用户。is_super 必须显式写 0(租户管理员不是平台超管,4.10)
            boolean generated = request.adminPassword() == null || request.adminPassword().isBlank();
            String rawPassword = generated ? PasswordGenerator.generate() : request.adminPassword();
            SysUser admin = new SysUser();
            admin.setUsername(request.adminUsername());
            admin.setPassword(passwordEncoder.encode(rawPassword));
            admin.setNickname(request.adminNickname() == null ? request.adminUsername() : request.adminNickname());
            admin.setStatus(1);
            admin.setIsSuper(0);
            admin.setMustChangePassword(generated ? 1 : 0);
            admin.setLoginFailCount(0);
            admin.setDeptId(dept.getId());
            admin.getRoleIds().add(role.getId());
            userRepository.save(admin);

            log.info("租户已创建 tenantId={} tenantCode={} packageId={} adminUserId={}",
                    tenant.getId(), tenant.getTenantCode(), tenantPackage.getId(), admin.getId());

            // 明文密码只在这里返回一次,不落库、不写日志(7.1.2)
            return new TenantCreateResponse(tenant.getId(), admin.getId(), admin.getUsername(),
                    generated ? rawPassword : null, generated, role.getId(), dept.getId());
        });
    }

    @Override
    public PageResult<TenantView> page(String tenantCode, Integer status, int pageNo, int pageSize) {
        QTenant qTenant = QTenant.tenant;
        BooleanBuilder where = new BooleanBuilder();
        if (tenantCode != null && !tenantCode.isBlank()) {
            where.and(qTenant.tenantCode.contains(tenantCode));
        }
        if (status != null) {
            where.and(qTenant.status.eq(status));
        }
        Page<Tenant> page = tenantRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1)));

        Set<Long> packageIds = page.getContent().stream()
                .map(Tenant::getPackageId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> packageNames = packageIds.isEmpty()
                ? Map.of()
                : packageRepository.findAllById(packageIds).stream()
                        .collect(Collectors.toMap(SysPackage::getId, SysPackage::getPackageName, (a, b) -> a));

        List<TenantView> views = page.getContent().stream()
                .map(tenant -> new TenantView(tenant.getId(), tenant.getTenantCode(), tenant.getTenantName(),
                        tenant.getStatus(), tenant.getPackageId(),
                        tenant.getPackageId() == null ? null : packageNames.get(tenant.getPackageId()),
                        tenant.getExpireTime(), tenant.getCreateTime()))
                .toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    /**
     * 4.8 套餐变更:新增只同步默认管理员角色,收回对**该租户全部角色**立即生效。
     */
    @Override
    public void changePackage(Long tenantId, TenantPackageChangeRequest request) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Long oldPackageId = tenant.getPackageId();
        if (Objects.equals(oldPackageId, request.packageId())) {
            return;
        }
        SysPackage newPackage = packageRepository.findById(request.packageId())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUSINESS_ERROR, "目标套餐不存在"));

        Set<Long> oldMenus = menuIdsOfPackage(oldPackageId);
        Set<Long> newMenus = new HashSet<>(newPackage.getMenuIds());
        Set<Long> added = new LinkedHashSet<>(newMenus);
        added.removeAll(oldMenus);
        Set<Long> revoked = new LinkedHashSet<>(oldMenus);
        revoked.removeAll(newMenus);

        List<SysRole> roles = roleRepository.findByTenantId(tenantId);

        // 新增:只给默认管理员角色。自定义角色不动 —— 新增权限是"锦上添花",
        // 不主动帮自定义角色加不会有安全风险(4.8)
        if (!added.isEmpty()) {
            roles.stream().filter(SysRole::isDefaultRole).findFirst().ifPresent(defaultRole -> {
                defaultRole.getMenuIds().addAll(added);
                roleRepository.save(defaultRole);
            });
        }
        // 收回:全租户所有角色(含自定义角色)。只收默认角色等于降级没生效 ——
        // 任何一个绕过默认角色的自定义角色都还能访问(4.8 的核心判断)
        if (!revoked.isEmpty()) {
            roleRepository.revokeMenusFromTenant(revoked, tenantId);
        }

        recordChange(tenantId, SysTenantPackageChange.TRIGGER_TENANT_SWITCH, oldPackageId, newPackage.getId(), added, revoked);
        tenant.setPackageId(newPackage.getId());

        // 5.5:权限已变,INCR 该租户的权限版本号,新权限在下一个请求生效
        permissionCacheService.invalidateTenant(tenantId);
        log.info("租户套餐已变更 tenantId={} {} -> {} 新增={} 收回={}",
                tenantId, oldPackageId, newPackage.getId(), added.size(), revoked.size());
    }

    @Override
    public void changeStatus(Long tenantId, int status) {
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        tenant.setStatus(status);

        // 4.11:先让缓存失效,该租户所有用户的下一个请求就会重新查库并拿到新状态(立即生效)
        tenantLookup.evict(new TenantSnapshot(tenant.getId(), tenant.getTenantCode(), tenant.getStatus(),
                tenant.getExpireTime(), tenant.getPackageId()));

        if (status == 0) {
            // 7.1.3 的撤销时机表:禁用租户时主动撤销该租户所有用户的刷新令牌。
            // 即使不撤,刷新接口的租户状态校验也会拦下(7.1.3 第 3 步),这里是"不让凭据留着"的第二道
            for (SysUser user : userRepository.findByTenantId(tenantId)) {
                refreshTokenService.revokeAll(user.getId());
            }
            log.info("租户已禁用并撤销全部刷新令牌 tenantId={}", tenantId);
        }
    }

    /** 套餐对应的菜单集合;{@code packageId} 为空表示"不限",按全部非平台菜单处理(4.8 步骤 1)。 */
    private Set<Long> menuIdsOfPackage(Long packageId) {
        if (packageId == null) {
            return menuRepository.findAllNonPlatform().stream()
                    .map(menu -> menu.getId())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return packageRepository.findById(packageId)
                .map(pkg -> new LinkedHashSet<>(pkg.getMenuIds()))
                .orElseGet(LinkedHashSet::new);
    }

    /**
     * 写审计记录(4.8 步骤 5)。菜单 ID 列表按建表脚本的约定存 JSON 数组;
     * 这里手工拼字符串,不为一个审计字段引入序列化依赖。
     */
    private void recordChange(Long tenantId, int triggerType, Long oldPackageId, Long newPackageId,
                              Collection<Long> added, Collection<Long> revoked) {
        SysTenantPackageChange change = new SysTenantPackageChange();
        change.setTenantId(tenantId);
        change.setTriggerType(triggerType);
        change.setOldPackageId(oldPackageId);
        change.setNewPackageId(newPackageId);
        change.setAddedMenuIds(toJsonArray(added));
        change.setRevokedMenuIds(toJsonArray(revoked));
        changeRepository.save(change);
    }

    private String toJsonArray(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

}
