/**
 * 平台/租户管理域的实体与仓储接口(tenant、sys_user、sys_role、sys_dept、sys_menu、sys_dict_* 等)。
 *
 * <p>本包只放这一件事。两点容易找错地方的说明:
 * <ul>
 *   <li><b>租户过滤与审计的基类不在本包</b>,在 {@code com.minimall.infra.persistence}
 *       ({@code BaseTenantEntity}/{@code BaseAuditEntity}/{@code OwnedEntity})。原因见该包说明:
 *       它们被 mall 域的 28 个实体一起继承,放在这里会让 {@code mall.domain} 依赖 {@code sys.domain}</li>
 *   <li><b>过滤器定义也不在本包</b>:{@code tenantFilter}/{@code dataScopeFilter} 的 {@code @FilterDef}
 *       同样声明在 {@code infra.persistence} 的 package-info 上。过滤器是全局注册的,声明一次即可被
 *       所有域的实体用 {@code @Filter(name = ...)} 引用;放在此处会给人"只有 sys 域生效"的误导</li>
 * </ul>
 */
package com.minimall.sys.domain;
