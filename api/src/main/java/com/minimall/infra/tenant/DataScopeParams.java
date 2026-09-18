package com.minimall.infra.tenant;

/**
 * 数据权限过滤的绑定参数(架构文档 5.3)。
 *
 * <p>五个档位共用一条 SQL 条件(见 {@code com.minimall.domain.sys.package-info} 里的 dataScopeFilter 定义),
 * 靠 {@code dataScope} 这个参数决定哪一支生效。这样做的原因:Hibernate Filter 的条件是静态 SQL 片段,
 * 按档位拆成五个 filter 会让"到底该 enable 哪个"散落成五处判断;一条条件 + 参数更好测,也更不容易漏 enable。
 *
 * @param dataScope     最终生效的档位:1-仅本人 2-本部门 3-本部门及以下 4-自定义部门 5-全部。
 *                      **多角色时取所有有效角色的最大值(最宽)**,见 5.3。
 *                      0 表示"无任何数据权限",五个分支全为 false,查询结果为空集
 * @param currentUserId 当前用户 ID
 * @param currentDeptId 当前用户所在部门 ID,可为空({@code dept_id} 为空的用户按 2/3/4 档会命中空集)
 * @param deptPathLike  用于"本部门及以下"的 LIKE 模式,形如 {@code 1,5,8,%}
 *                      (当前部门的 ancestors + id + 逗号),为空表示无法计算
 */
public record DataScopeParams(int dataScope, Long currentUserId, Long currentDeptId, String deptPathLike) {

    /** 档位常量,与建表脚本 sys_role.data_scope 的注释保持一致。 */
    public static final int SCOPE_DENY_ALL = 0;
    public static final int SCOPE_SELF = 1;
    public static final int SCOPE_DEPT = 2;
    public static final int SCOPE_DEPT_AND_BELOW = 3;
    public static final int SCOPE_CUSTOM_DEPT = 4;
    public static final int SCOPE_ALL = 5;

    /**
     * 拒绝一切:算不出数据权限时的兜底。
     *
     * <p>为什么兜底是"空集"而不是"全部":后者一旦生效就是越权(能看到别的部门数据),
     * 而前者最坏只是"查不到数据",是能被立刻发现的故障。这与 4.2 的默认拒绝是同一个取向。
     */
    public static DataScopeParams denyAll() {
        return new DataScopeParams(SCOPE_DENY_ALL, null, null, null);
    }

    /**
     * 拒绝"别人的数据",但**自己的那一行仍然可读**。
     *
     * <p>用在"用户存在、但没有任何有效角色"这种情形:按角色算不出任何范围,业务数据理应全空,
     * 但自己的账号必须可读 —— 否则一个没有角色的用户连初始密码都改不了(改密要按 ID 加载当前用户),
     * 会卡死在"能登录、什么都做不了"的状态里。
     *
     * <p>与 {@link #denyAll()} 的唯一区别是带上了 {@code currentUserId}:条件里的
     * {@code id = :currentUserId} 那一支因此才可能为真。id 为空(没有任何身份)时只能真的返回空集。
     */
    public static DataScopeParams denyAllButSelf(Long currentUserId, Long currentDeptId) {
        return new DataScopeParams(SCOPE_DENY_ALL, currentUserId, currentDeptId, null);
    }
}
