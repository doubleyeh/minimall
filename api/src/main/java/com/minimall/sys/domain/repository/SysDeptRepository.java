package com.minimall.sys.domain.repository;

import com.minimall.sys.domain.QSysDept;
import com.minimall.sys.domain.SysDept;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 部门仓储(架构文档 5.1、5.3、5.6)。
 */
public interface SysDeptRepository extends JpaRepository<SysDept, Long>, QuerydslPredicateExecutor<SysDept> {

    /**
     * 按 ID 取部门,**必须走查询而不是主键直查**(与 {@code SysUserRepository#findById} 同因同果)。
     *
     * <p>部门虽然不参与数据权限过滤,但它受**租户过滤**:默认的 {@code findById} 不带租户条件,
     * 于是"校验部门是否存在"这类前置检查会把别的租户的部门 ID 当成合法值放过去,
     * 用户的 {@code dept_id}、角色的自定义部门集合都可能挂到别的租户下(4.6.1 明令禁止)。
     */
    @Override
    default Optional<SysDept> findById(Long id) {
        return findOne(QSysDept.sysDept.id.eq(id));
    }

    List<SysDept> findByTenantIdOrderBySortOrderAsc(Long tenantId);

    List<SysDept> findByParentId(Long parentId);

    boolean existsByParentId(Long parentId);

    /** 同租户内部门名唯一(建表脚本 uk_tenant_dept_name)。 */
    boolean existsByTenantIdAndDeptName(Long tenantId, String deptName);

    /**
     * 按祖级链前缀取子树 —— 部门被移动到新上级时,子孙的 ancestors 都要跟着改(5.1)。
     */
    List<SysDept> findByAncestorsStartingWith(String ancestorsPrefix);

    /**
     * "本部门及以下"的部门 ID(5.3 的第 3 档)。
     *
     * <p>用 {@code ancestors LIKE} 一条查询拿到整棵子树,不做递归——这正是
     * {@code sys_dept.ancestors} 冗余祖级链存在的理由。
     *
     * @param deptPathPrefix 形如 {@code 1,5,8,},由服务端用"父链 + 本部门 ID + 逗号"拼出
     */
    /*
     * 比较前必须给 ancestors 补一个尾逗号:直接子部门的 ancestors 是 "1,5"(没有尾逗号),
     * 而前缀是 "1,5,8," 这种带逗号的形状,不补的话 '1,5' like '1,5,8,%' 恒为 false ——
     * 结果是"本部门及以下"漏掉**直接子部门**,只查到孙部门及更深。补逗号后:
     * '1,5,' like '1,5,8,%' = true(直接子)、'1,5,8,' = true(孙),平级 '1,6,' = false;
     * 同时逗号也挡住了"部门 12 与 123 前缀相同"这类误匹配。
     */
    @Query("select d.id from SysDept d where concat(d.ancestors, ',') like concat(:deptPathPrefix, '%')")
    List<Long> findDescendantIds(@Param("deptPathPrefix") String deptPathPrefix);
}
