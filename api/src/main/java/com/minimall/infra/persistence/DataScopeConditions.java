package com.minimall.infra.persistence;

/**
 * 数据权限过滤条件(架构文档 5.3 的映射表,落成可复用的 SQL 片段)。
 *
 * <p>为什么是一条条件而不是五个 filter:Hibernate Filter 的条件是静态 SQL 片段,
 * 按档位拆成五个 filter 会让"到底该 enable 哪个"散落成五处判断。这里用 {@code :dataScope}
 * 参数决定哪一支生效,参数由 {@link com.minimall.infra.tenant.DataScopeProvider} 算出来。
 *
 * <p>几个必须注意的点:
 * <ul>
 *   <li><b>多角色取最大值(最宽)</b>由 provider 完成,这里只负责"按某一档过滤"(见 5.3)</li>
 *   <li>{@code dataScope = 0}(算不出参数时的默认拒绝)会让五个分支全为 false,
 *       查询结果为空集——这是有意的兜底,不要为了"让页面有数据"把它改成放行</li>
 *   <li>"自定义部门"用**子查询**表达,而不是传 roleId/deptId 列表进来:
 *       Hibernate 的 {@code Filter.setParameter} 不支持集合参数</li>
 *   <li>本条件依赖表上有 {@code dept_id} 与 {@code create_by} 两列,使用时必须确认——这也是
 *       4.6.1 里"数据权限 = 是"那一列存在的意义</li>
 * </ul>
 *
 * <p>它是编译期常量,所以可以直接被各实体上的 {@code @Filter(condition = ...)} 引用,
 * 不必在每张业务表上重复一遍 SQL。
 */
public final class DataScopeConditions {

    /**
     * 五个档位分支,两处条件共用同一份,避免"两个 SQL 片段各改一半"的经典事故。
     *
     * <p>第 3 档(本部门及以下)这条子查询有两个不能省的细节,都是实测踩出来的:
     * <ol>
     *   <li>{@code concat(d.ancestors, ',')}:直接子部门的 {@code ancestors} 形如 {@code "1,5"}
     *       (不带尾逗号),而前缀参数形如 {@code "1,5,8,"}(带逗号),不补逗号的话
     *       {@code '1,5' like '1,5,8,%'} 恒为 false —— **漏掉直接子部门**。补上尾逗号还顺带挡住
     *       "部门 12 与 123 前缀相同"这类误匹配</li>
     *   <li>{@code concat(:deptPathLike, '%')}:参数是**前缀**({@code "1,5,8,"}),`%` 必须在这里拼。
     *       少了它变成精确匹配,只能命中直接子部门、**匹配不到更深层的子孙** ——
     *       两级部门时看不出问题,四层深链上"本部门及以下"就变成"本部门及儿子"(见 8.3 第一条)</li>
     * </ol>
     */
    private static final String SCOPE_BRANCHES = "(:dataScope = 5)"
            + " or (:dataScope = 1 and create_by = :currentUserId)"
            + " or (:dataScope = 2 and dept_id = :currentDeptId)"
            + " or (:dataScope = 3 and (dept_id = :currentDeptId"
            + "     or dept_id in (select d.id from sys_dept d"
            + "         where concat(d.ancestors, ',') like concat(:deptPathLike, '%'))))"
            + " or (:dataScope = 4 and dept_id in (select rd.dept_id from sys_role_dept rd"
            + "     join sys_user_role ur on ur.role_id = rd.role_id where ur.user_id = :currentUserId))";

    public static final String SQL = "(" + SCOPE_BRANCHES + ")";

    /**
     * 在 {@link #SQL} 基础上补一条"自己的账号永远可见",给 {@code sys_user} 用。
     *
     * <p>为什么必须有这一条:数据权限的"仅本人"档是按 {@code create_by = 当前用户} 判断的,
     * 而**用户不是自己创建的**。于是"仅本人"档的用户连自己那一行 sys_user 都读不到 ——
     * 改密(按 ID 加载当前用户)、读自己的权限快照这些动作会全部失败,而报错看上去像"用户不存在"。
     *
     * <p>这与 5.3 不冲突:数据权限约束的是"能看别人哪些数据",不是"能不能看自己"。
     * {@code currentUserId} 为空时(身份前置查询)这一支恒为 false,不会因此放开任何数据。
     */
    public static final String SQL_SELF_VISIBLE = "(" + SCOPE_BRANCHES + " or id = :currentUserId)";

    private DataScopeConditions() {
    }
}
