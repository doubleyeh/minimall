package com.minimall.service.sys.support;

import com.minimall.domain.sys.SysDept;
import com.minimall.domain.sys.SysRole;
import com.minimall.domain.sys.SysUser;
import com.minimall.domain.sys.repository.SysDeptRepository;
import com.minimall.domain.sys.repository.SysRoleRepository;
import com.minimall.domain.sys.repository.SysUserRepository;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.DataScopeParams;
import com.minimall.infra.tenant.DataScopeProvider;
import org.springframework.stereotype.Service;

import java.util.Collection;

/**
 * 数据权限参数的真实实现(架构文档 5.3)。
 *
 * <p>核心规则:**多角色取最大值(最宽)**。这里刻意不"取最严":
 * 给一个用户挂上"全部"角色本身就是一次明确的授权决定,系统不该在运行时悄悄打折;
 * 这也与菜单权限的并集语义一致。副作用是"加角色会放宽可见范围",
 * 所以分配角色时要留意宽窄方向(见 5.3 的提醒)。
 *
 * <p>兜底一律指向"查不到别人的数据":没有当前用户、用户查不到时返回
 * {@link DataScopeParams#denyAll()}(真正的空集),用户存在但没有任何有效角色时返回
 * {@link DataScopeParams#denyAllButSelf}(别人的数据为空,自己的账号仍可读)。
 * **不要为了"让页面有数据"把它们改成放行**——那是最直接的越权入口。
 */
@Service
public class DataScopeProviderImpl implements DataScopeProvider {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final SysDeptRepository deptRepository;

    public DataScopeProviderImpl(SysUserRepository userRepository,
                                 SysRoleRepository roleRepository,
                                 SysDeptRepository deptRepository) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.deptRepository = deptRepository;
    }

    @Override
    public DataScopeParams current() {
        Long userId = AuditContext.currentUserId();
        if (userId == null) {
            // 没有请求周期(或异步场景没传审计快照):无从判断数据范围,默认拒绝
            return DataScopeParams.denyAll();
        }

        SysUser user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return DataScopeParams.denyAll();
        }

        if (user.isSuperUser()) {
            // 超管:租户过滤已豁免(4.2),数据权限同样不生效(4.10)。
            // 这里显式给"全部",是为了让"超管 + 无 dataScope 参数"这种组合保持一致的语义
            return new DataScopeParams(DataScopeParams.SCOPE_ALL, userId, user.getDeptId(), null);
        }

        int scope = maxDataScope(user);
        if (scope == DataScopeParams.SCOPE_DENY_ALL) {
            // 用户存在但没有有效角色:业务数据全空,但要保住"自己的账号可读"(否则改不了初始密码)
            return DataScopeParams.denyAllButSelf(userId, user.getDeptId());
        }
        return new DataScopeParams(scope, userId, user.getDeptId(), resolveDeptPathLike(user));
    }

    private int maxDataScope(SysUser user) {
        Collection<Long> roleIds = user.getRoleIds();
        if (roleIds == null || roleIds.isEmpty()) {
            return DataScopeParams.SCOPE_DENY_ALL;
        }
        int max = DataScopeParams.SCOPE_DENY_ALL;
        for (SysRole role : roleRepository.findByIdInAndTenantId(roleIds, user.getTenantId())) {
            if (!role.isEnabled() || role.getDataScope() == null) {
                continue;
            }
            max = Math.max(max, role.getDataScope());
        }
        return max;
    }

    /**
     * "本部门及以下"(第 3 档)需要的 LIKE 前缀,形如 {@code 1,5,8,}:
     * 当前部门的 ancestors + 自己的 id + 逗号,配一条 {@code LIKE '前缀%'} 就能拿到整棵子树(5.1)。
     */
    private String resolveDeptPathLike(SysUser user) {
        if (user.getDeptId() == null) {
            // 没部门的用户在第 2/3/4 档下会命中空集,这是有意的(5.3:不要把空部门特判成"看全部")
            return null;
        }
        SysDept dept = deptRepository.findById(user.getDeptId()).orElse(null);
        if (dept == null) {
            return null;
        }
        // 前缀拼接收敛在实体上(那里曾经因为少了分隔逗号,导致"本部门及以下"看不到直接子部门)
        return dept.pathPrefix();
    }
}
