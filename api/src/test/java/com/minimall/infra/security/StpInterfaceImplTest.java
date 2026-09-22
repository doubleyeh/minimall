package com.minimall.infra.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sa-Token 权限适配器的边界(架构文档 5.2)。
 *
 * <p>这个类只有"把 loginId 转成 userId、把权限集合转成 List"这点代码,但**它决定了
 * {@code @SaCheckPermission} 在异常输入下是放行还是拒绝** —— 方向搞反就是越权:
 * <ul>
 *   <li>loginId 为 null 或不是数字时返回空集合 → 校验"没有这个权限" → 拒绝。反过来(返回全量)就全通了</li>
 *   <li>{@link #getRoleList} 必须返回空 —— 本方案用 perm_code 授权,权限缓存里没有角色标识。
 *       这里一旦顺手返回角色,{@code @SaCheckRole} 会从"一直拒绝"变成"看起来能用",
 *       而实际数据是空的,于是又退回到一直拒绝 —— 这个坑写在实现注释里,这里把它钉住</li>
 * </ul>
 */
class StpInterfaceImplTest {

    private static final PermissionProvider PROVIDER = userId ->
            new PermissionProvider.PermissionData(Set.of("system:user:list", "mall:order:ship"), Set.of());

    private final StpInterfaceImpl adapter = new StpInterfaceImpl(PROVIDER);

    @Test
    @DisplayName("权限集合:从 PermissionProvider 取到的 perm_code 原样返回")
    void permissionListComesFromProvider() {
        assertThat(adapter.getPermissionList(1001L, "login"))
                .containsExactlyInAnyOrder("system:user:list", "mall:order:ship");
    }

    @Test
    @DisplayName("loginId 为 null 或非数字时返回空权限(校验会据此拒绝,不能返回全量)")
    void unparsableLoginIdYieldsNoPermission() {
        assertThat(adapter.getPermissionList(null, "login")).isEmpty();
        assertThat(adapter.getPermissionList("不是数字", "login")).isEmpty();
    }

    @Test
    @DisplayName("角色集合恒为空:本方案用 perm_code 授权,@SaCheckRole 不该被当成可用的")
    void roleListIsAlwaysEmpty() {
        assertThat(adapter.getRoleList(1001L, "login")).isEmpty();
    }
}
