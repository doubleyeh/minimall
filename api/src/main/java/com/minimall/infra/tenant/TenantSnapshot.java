package com.minimall.infra.tenant;

import java.time.LocalDateTime;

/**
 * 租户状态快照(架构文档 4.11)。
 *
 * <p>为什么要这个快照而不是直接用实体:租户校验发生在**每个请求**上,不能每次都读库
 * (那会把 {@code tenant} 表变成热点);而这个校验只需要几个字段,给它一个专用的小对象,
 * 也顺便让 {@code TenantWebFilter} 不必依赖 domain 层。
 *
 * @param id         租户 ID
 * @param tenantCode 租户编码(登录时按它定位)
 * @param status     0-禁用 1-正常
 * @param expireTime 为空表示不过期
 * @param packageId  套餐 ID,为空表示"不限"(见 4.8)
 */
public record TenantSnapshot(Long id, String tenantCode, Integer status, LocalDateTime expireTime, Long packageId) {

    /**
     * 租户当前是否可用(启用且未过期)。
     *
     * <p>注意:**不可用时对外只返回"用户名或密码错误"或 401 统一文案**,
     * 不能提示"租户已过期"——那等于提供一个批量探测有效 tenantCode 的接口(见 7.1.1)。
     */
    public boolean usable() {
        return status != null && status == 1
                && (expireTime == null || expireTime.isAfter(LocalDateTime.now()));
    }
}
