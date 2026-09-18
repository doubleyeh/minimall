package com.minimall.infra.security;

import java.util.Set;

/**
 * 权限数据来源(架构文档 5.2、5.5)。
 *
 * <p>接口定义在 infra、实现在 service,理由与 {@code DataScopeProvider}、{@code TenantLookup} 一致:
 * Sa-Token 的 {@code StpInterface} 适配器属于 infra,它不该直接做业务查询;
 * 而"权限怎么算、怎么缓存、超管怎么短路"是业务规则,放在 service。
 *
 * <p>这样切分之后,{@link StpInterfaceImpl} 只剩"把 loginId 转成 userId、把结果转成 List"这点代码,
 * 不值得为它写测试;真正需要测的算权限逻辑可以在 service 层直接测(见 8.2 权限矩阵用例)。
 */
public interface PermissionProvider {

    /**
     * 加载某个用户的权限集合。
     *
     * <p>实现必须处理超管({@code sys_user.is_super = 1}):**短路返回全量 perm_code,不查角色、不查缓存**
     * (见 4.10)。走角色计算只会引入"平台租户的角色被误改导致超管进不去后台"这类运维事故。
     */
    PermissionData load(Long userId);

    /**
     * @param permCodes 该用户的权限码集合
     * @param menus     该用户可见的菜单标识(前端渲染动态路由用,见 7.1.1)
     */
    record PermissionData(Set<String> permCodes, Set<String> menus) {

        public static PermissionData empty() {
            return new PermissionData(Set.of(), Set.of());
        }
    }
}
