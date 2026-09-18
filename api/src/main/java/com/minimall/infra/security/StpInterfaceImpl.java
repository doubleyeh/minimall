package com.minimall.infra.security;

import cn.dev33.satoken.stp.StpInterface;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Sa-Token 的权限数据适配器(架构文档 5.2)。
 *
 * <p>{@code @SaCheckPermission} 只负责"校验",具体权限集合从哪来由这个 SPI 提供。
 * 这里只做两件事:把 loginId 转成 userId、把权限集合转成 List;真正的计算与缓存在
 * {@link PermissionProvider} 的实现里(service 侧),所以这段代码没有值得单测的逻辑。
 *
 * <p><b>{@link #getRoleList} 故意返回空</b>:本方案的授权判断统一用 {@code perm_code}
 * ({@code @SaCheckPermission}),权限缓存里也没有存角色标识。如果将来要用 {@code @SaCheckRole},
 * 必须同时把角色的 {@code role_key} 纳入权限缓存并在这里返回,否则那个注解会**一直拒绝**
 * ——这是个很容易踩的坑,所以写在这里而不是留给下一个人去猜。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    private final PermissionProvider permissionProvider;

    public StpInterfaceImpl(PermissionProvider permissionProvider) {
        this.permissionProvider = permissionProvider;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = toUserId(loginId);
        if (userId == null) {
            return List.of();
        }
        return List.copyOf(permissionProvider.load(userId).permCodes());
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return List.of();
    }

    private Long toUserId(Object loginId) {
        if (loginId == null) {
            return null;
        }
        try {
            // StpUtil.login(userId) 传的是 Long,Sa-Token 内部可能以字符串形式保存 loginId
            return Long.valueOf(String.valueOf(loginId));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
