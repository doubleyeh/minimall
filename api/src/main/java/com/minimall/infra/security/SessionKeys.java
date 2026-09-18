package com.minimall.infra.security;

/**
 * Sa-Token 会话扩展数据的 key(架构文档 7.1.1 第 5 步写入)。
 *
 * <p>集中在一处的原因:写的地方(登录、刷新)和读的地方(租户过滤器、强制改密拦截)分散在
 * 不同的类里,字符串写错不会报错,只会表现为"读不到 → 租户为空 → 查不到数据"这类难查的现象。
 *
 * <p>注意会话里**不放权限集合**(那走独立的 Redis 缓存,便于按版本号失效,见 5.5),
 * 只放"每个请求都要用、且极小的"身份字段。
 */
public final class SessionKeys {

    /** 租户 ID:租户过滤器每请求读一次(4.2)。 */
    public static final String TENANT_ID = "tenantId";

    /** 是否平台超管:决定"是否豁免租户过滤"(4.10)。取自 sys_user.is_super,不是租户级标志。 */
    public static final String SUPER_USER = "isSuperUser";

    /**
     * 本次登录签发的刷新令牌。
     *
     * <p>放进会话是为了让**登出能精确撤销本次登录的那一张**(而不是把该用户所有端的令牌一起废掉,
     * 那会让其他设备在访问令牌过期后被迫重新登录,见 7.1.3 的撤销时机表)。
     */
    public static final String REFRESH_TOKEN = "refreshToken";

    /** 是否必须先改密:由强制改密拦截器每请求读一次(7.1.2)。 */
    public static final String MUST_CHANGE_PASSWORD = "mustChangePassword";

    private SessionKeys() {
    }
}
