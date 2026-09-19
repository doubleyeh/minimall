package com.minimall.sys.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 保存套餐菜单请求——这是架构文档 4.8.1 的**触发源二(平台改套餐内容)**。
 *
 * <p>"改了马上生效"是明确决策:保存成功后,服务端立即对所有绑定该套餐的租户逐个执行差异同步
 * (新增只写默认管理员角色;收回对该租户全部角色立即删除),每个租户一个事务,单租户失败不影响其他租户。
 *
 * <p>服务端校验(5.2.1):列表里不能有 {@code is_platform = 1} 的平台专用菜单,且菜单树的父链必须完整
 * (勾了子菜单就要包含其全部祖先,否则前端渲染出的菜单树会断)。
 */
public record PackageMenuSaveRequest(

        @NotNull(message = "菜单ID列表不能为null,清空套餐菜单请传空数组")
        List<Long> menuIds
) {
}
