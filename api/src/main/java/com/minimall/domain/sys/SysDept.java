package com.minimall.domain.sys;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 部门(架构文档 5.1、5.3)。
 *
 * <p>{@code ancestors} 是冗余的祖级链(逗号分隔,如 {@code 0,1,5}),让"本部门及以下"能用一条
 * LIKE 查询表达,不用递归。它**只能由服务端维护**:客户端能改它等于能伪造层级关系,
 * 进而绕过"本部门及以下"的数据权限。
 *
 * <p>不参与数据权限过滤(4.6.1):部门树是组织架构,租户内全员可见;能不能改由菜单权限控制。
 */
@Entity
@Table(name = "sys_dept")
@Getter
@Setter
public class SysDept extends BaseTenantEntity {

    /** 0 表示根部门。每个租户在建租户时就有一个根部门(4.7 第 5 步),否则 2/3/4 档数据权限无部门可用 */
    @Column(name = "parent_id", nullable = false)
    private Long parentId;

    @Column(name = "ancestors", nullable = false, length = 255)
    private String ancestors;

    @Column(name = "dept_name", nullable = false, length = 64)
    private String deptName;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    @Column(name = "status", nullable = false)
    private Integer status;

    public boolean isRoot() {
        return parentId != null && parentId == 0L;
    }

    /**
     * 用于 LIKE 的前缀:自己的祖级链 + 自己 id + 逗号,例如 {@code 1,5,8,}。
     *
     * <p>配套的查询必须写成 {@code concat(子部门.ancestors, ',') like 前缀 || '%'}:
     * 直接子部门的 ancestors 是 {@code 1,5}(没有尾逗号),不给它补逗号就永远匹配不上自己的直接子部门。
     */
    public String pathPrefix() {
        return pathPrefix(ancestors, getId());
    }

    /**
     * 静态形式:按给定的祖级链与部门 ID 拼前缀。
     *
     * <p>为什么还要静态版本:移动部门时要用**新的**祖级链去算子孙的新前缀,那时实体上的值还没更新。
     *
     * <p>为什么要收敛到这里:数据权限的"本部门及以下"与部门移动的"子孙链替换"原本各写了一份拼接,
     * 其中一份**漏了祖级链与本部门之间的逗号**(拼成 {@code 1,58,}),
     * 后果是"本部门及以下"整片看不到直接子部门(实测踩过)。同一件事只留一处实现,就不会有第二次。
     */
    public static String pathPrefix(String ancestors, Long deptId) {
        String chain = ancestors == null ? "" : ancestors;
        return chain.isEmpty() ? deptId + "," : chain + "," + deptId + ",";
    }
}
