package com.minimall.api.sys.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 变更租户套餐请求(架构文档 4.8)。
 *
 * <p>差异计算由服务端完成:升级只把新增菜单同步给默认管理员角色,降级对该租户**全部角色**立即收回,
 * 两者不对称是有意为之(见 4.8 的理由)。
 */
public record TenantPackageChangeRequest(

        /** 目标套餐。不支持改为"不限"(NULL),避免每次变更都要处理一次全量差异(见架构文档 4.8 步骤 1) */
        @NotNull(message = "目标套餐不能为空")
        Long packageId
) {
}
