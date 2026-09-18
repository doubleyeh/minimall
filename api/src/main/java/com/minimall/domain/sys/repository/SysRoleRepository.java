package com.minimall.domain.sys.repository;

import com.minimall.domain.sys.QSysRole;
import com.minimall.domain.sys.SysRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 角色仓储(架构文档 4.7、4.8、5.3)。
 */
public interface SysRoleRepository extends JpaRepository<SysRole, Long>,
        org.springframework.data.querydsl.QuerydslPredicateExecutor<SysRole> {

    /**
     * 按 ID 取角色,**必须走查询而不是主键直查**(与 {@code SysUserRepository#findById} 同因同果)。
     *
     * <p>Hibernate 的 {@code @Filter} 不作用于 {@code EntityManager.find()}:默认实现生成的
     * {@code where id=?} 不带租户条件,别的租户只要拿到一个角色 ID 就能改到它(7.3 的越权防护失效)。
     */
    @Override
    default Optional<SysRole> findById(Long id) {
        return findOne(QSysRole.sysRole.id.eq(id));
    }

    /** 同一租户内角色标识唯一(建表脚本 uk_tenant_role_key);这里做"友好提示",最终由唯一索引兜底。 */
    Optional<SysRole> findByTenantIdAndRoleKey(Long tenantId, String roleKey);

    /**
     * 默认管理员角色(4.8:套餐变更时只自动同步这个角色)。
     *
     * <p>这里用显式 JPQL 而不是派生方法名 {@code findByTenantIdAndIsDefault}:
     * 字段名以 {@code is} 开头,派生方法名解析时容易被当成 Is 关键字 + Default 属性,
     * 直接写条件最省事也最不会踩解析歧义。
     */
    @Query("select r from SysRole r where r.tenantId = :tenantId and r.isDefault = 1")
    Optional<SysRole> findDefaultRole(@Param("tenantId") Long tenantId);

    /** 4.8 的收回动作:对该租户下**全部角色**(含自定义角色)生效,不区分默认角色。 */
    @Query("select r from SysRole r where r.tenantId = :tenantId")
    List<SysRole> findByTenantId(@Param("tenantId") Long tenantId);

    List<SysRole> findByIdInAndTenantId(Collection<Long> ids, Long tenantId);

    /**
     * 直接查角色被授权的菜单 ID。
     *
     * <p>存在的意义:断言"某个角色的授权集合"时,如果用 {@code findById(...).getMenuIds()},
     * 那是懒加载集合,一旦调用方不在事务里就会炸 {@code LazyInitializationException};
     * 而这条查询自身在 Spring Data 的只读事务里执行,返回的是普通 ID 列表,谁都能放心用。
     */
    @Query("select m from SysRole r join r.menuIds m where r.id = :roleId")
    List<Long> findMenuIdsOfRole(@Param("roleId") Long roleId);

    /**
     * 4.8 步骤 4 的收回动作:把指定菜单从**该租户全部角色**上摘掉。
     *
     * <p>这是本方案里少数几处原生 SQL 之一,所以必须自己写租户条件 —— 关联表没有 tenant_id,
     * 用 {@code role_id in (子查询按 tenant_id 限定)} 把范围钉死。这正符合第 2 节的要求:
     * bulk 语句不受过滤器保护,租户条件必须手写。
     *
     * <p>{@code clearAutomatically} 必须开:bulk delete 不走持久化上下文,
     * 不清缓存的话,内存里那些角色的 {@code menuIds} 还是旧值,后续 flush 会把刚删掉的关联又插回去。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "delete from sys_role_menu where menu_id in (:menuIds) "
            + "and role_id in (select id from sys_role where tenant_id = :tenantId)", nativeQuery = true)
    int revokeMenusFromTenant(@Param("menuIds") Collection<Long> menuIds, @Param("tenantId") Long tenantId);

    /**
     * 删除菜单时的级联清理(5.6):菜单是平台级数据,删掉要让所有租户立刻生效。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "delete from sys_role_menu where menu_id = :menuId", nativeQuery = true)
    int deleteRoleMenusByMenuId(@Param("menuId") Long menuId);

    /**
     * 持有该角色的用户数(删除前的引用检查,5.6:有用户持有时拒绝删除,而不是静默剥夺用户权限)。
     *
     * <p>{@code sys_user_role} 是挂在 {@code SysUser} 上的 {@code @ElementCollection},
     * 删角色不会自动清理它(反向关联不受 JPA 管理),所以这里的计数与下面的删除都必须手写。
     */
    @Query(value = "select count(*) from sys_user_role where role_id = :roleId", nativeQuery = true)
    long countUsersOfRole(@Param("roleId") Long roleId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "delete from sys_user_role where role_id = :roleId", nativeQuery = true)
    int deleteUserRolesByRoleId(@Param("roleId") Long roleId);
}
