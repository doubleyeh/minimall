/**
 * sys 域的实体与仓储接口。
 *
 * <p>两个 Hibernate 过滤器定义在这里(package 级),而不是塞进实体基类,原因是它们的**适用范围不同**:
 * <ul>
 *   <li>{@code tenantFilter}:租户过滤。条件很简单,由 {@code BaseTenantEntity} 统一声明
 *       {@code @Filter},所以所有租户表自动生效(见 4.6.1 的表清单)</li>
 *   <li>{@code dataScopeFilter}:数据权限过滤。按 4.6.1,**只有该表参与数据权限时**才在实体上单独加
 *       {@code @Filter}(当前是 {@code SysUser},以及以后的业务表)。如果把它声明在基类上,
 *       {@code sys_role}/{@code sys_dept}/{@code sys_oper_log} 会一起被套上"按部门看"的语义,
 *       而没有业务意义(比如"只看自己创建的角色")</li>
 * </ul>
 *
 * <p><b>两个 filter 的绑定参数都是标量</b>(见 {@code DataScopeParams}):Hibernate 的
 * {@code Filter.setParameter} 不支持集合参数,所以"自定义部门"这一档用子查询
 * (join sys_role_dept + sys_user_role)表达,而不是传一个 roleId/deptId 列表进来。
 *
 * <p>过滤器**是否启用、参数绑什么值**由 {@code TenantFilterService} 决定,
 * 调用时机在每个事务边界({@code TenantFilterAspect}),不要在这里写死:
 * <ul>
 *   <li>超管({@code isSuperUser}):两个 filter 都不启用 —— 这是唯一的豁免</li>
 *   <li>其他情况:一律启用;{@code tenantId} 为空时绑一个不存在的哨兵值,使租户表查询返回空集(4.2 默认拒绝)</li>
 * </ul>
 */
@FilterDef(name = "tenantFilter",
        parameters = @ParamDef(name = "tenantId", type = Long.class))
@FilterDef(name = "dataScopeFilter",
        parameters = {
                @ParamDef(name = "dataScope", type = Integer.class),
                @ParamDef(name = "currentUserId", type = Long.class),
                @ParamDef(name = "currentDeptId", type = Long.class),
                @ParamDef(name = "deptPathLike", type = String.class)
        })
package com.minimall.domain.sys;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
