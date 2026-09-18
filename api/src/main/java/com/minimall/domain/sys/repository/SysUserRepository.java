package com.minimall.domain.sys.repository;

import com.minimall.domain.sys.QSysUser;
import com.minimall.domain.sys.SysUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 用户仓储(架构文档 1.1:简单 CRUD 用派生方法,动态条件叠加用 QueryDSL)。
 *
 * <p>继承 {@code QuerydslPredicateExecutor} 是为了"租户过滤 + 数据权限 + 业务条件"三者叠加的分页查询:
 * QueryDSL-JPA 生成的是 JPQL,租户/数据权限过滤器对它同样生效(见 4.2)。
 *
 * <p>注意 {@code findByTenantIdAndUsername} 里的 tenantId 条件看起来与租户过滤器重复,
 * 但登录场景**必须保留**:那个时候 TenantContext 刚被赋值、过滤器可能还绑着旧参数,
 * 靠它保证"一定按正确的租户查"(见 7.1.1 与 AuthServiceImpl 的说明)。
 */
public interface SysUserRepository extends JpaRepository<SysUser, Long>, QuerydslPredicateExecutor<SysUser> {

    /**
     * 按 ID 取用户,**必须走查询而不是主键直查**。
     *
     * <p>覆盖掉默认实现的原因:Hibernate 的 {@code @Filter}(租户过滤、数据权限过滤)
     * **只作用于查询**,对 {@code EntityManager.find()} 那种主键直查不生效。
     * 默认的 {@code findById} 生成的是 {@code select ... from sys_user where id=?} ——
     * 条件里没有任何租户与数据权限,于是 7.3 的"按 ID 加载必须先经过滤查询"等于没有落地:
     * 任何登录用户都能凭一个 ID 读到/改到别的租户、别的部门的行(实测:SQL 里确实没有过滤条件)。
     *
     * <p>代价是每次都发一条 SQL(不再吃持久化上下文的一级缓存)。这个取舍是刻意的:
     * 少一次查询,远不如"按 ID 越权"严重。
     *
     * <p>由此推出一条约定:**带过滤器的实体仓储都不要用 {@code findById} 的默认实现**,
     * 需要"跨过滤读取"时必须显式表达(超管上下文、或单独的专用方法),不能靠直查绕过去。
     */
    @Override
    default Optional<SysUser> findById(Long id) {
        return findOne(QSysUser.sysUser.id.eq(id));
    }

    Optional<SysUser> findByTenantIdAndUsername(Long tenantId, String username);

    long countByTenantId(Long tenantId);

    /**
     * 按租户取全部用户。
     *
     * <p>用途:超管禁用租户时,要主动撤销该租户**所有用户**的刷新令牌(7.1.3 的撤销时机表)。
     * 这个查询是平台级运维动作,调用方是超管(租户过滤已豁免),所以不受数据权限收窄。
     */
    List<SysUser> findByTenantId(Long tenantId);

    /** 部门下的用户数:删除部门前的引用检查(5.6:有用户时拒绝删除,不做级联裁剪)。 */
    long countByDeptId(Long deptId);
}
