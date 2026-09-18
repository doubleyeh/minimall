package com.minimall.infra.security;

import java.time.Instant;

/**
 * 刷新令牌载荷(架构文档 7.1.3)。
 *
 * <p>它承载了"刷新接口在没有登录态的情况下需要知道的全部信息":谁、属于哪个租户、是不是超管。
 * 刷新接口是白名单路径(访问令牌此时通常已过期),租户上下文只能从这里来,不能从会话来——
 * 拿到载荷后要用 {@code TenantContext.runAsTenant(...)} 显式进入租户上下文,和支付回调(6.1)同一类处理。
 *
 * <p>{@code issuedAt} 不参与鉴权判断,只在排查"这张令牌是几点签发的"时有用
 * (比如怀疑客户端拿着几天前的令牌在刷新)。
 */
public record RefreshTokenPayload(Long userId, Long tenantId, boolean superUser, Instant issuedAt) {
}
