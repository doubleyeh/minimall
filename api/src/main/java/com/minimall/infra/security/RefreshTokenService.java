package com.minimall.infra.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * 刷新令牌的签发与轮换(架构文档 7.1.3)。
 *
 * <p>职责边界:本类只负责"令牌本身的机制"——签发、一次性轮换、重放判定。
 * **业务判定不在这里**:"租户/用户是否可用""要不要踢掉该用户全部会话"由调用方
 * ({@code AuthService})决定,因为它要查库、要用 Sa-Token 踢人。
 * 这样切分的好处是机制部分不需要 DB 就能测(见 8.1 用例 20 的实现方式)。
 *
 * <p>令牌本身是 32 字节随机数的 Base64URL 编码(43 个字符),**不是 JWT**:
 * 它必须能被服务端撤销,而 JWT 无状态带来的"免查询"收益在这里没有价值,
 * 反倒要多背一套签名与密钥轮换的维护面(见 7.1.3 的理由)。
 */
@Component
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenStore store;
    private final RefreshTokenProperties properties;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenStore store, RefreshTokenProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * 签发一张刷新令牌(登录成功、以及每次成功轮换时调用)。
     */
    public IssuedRefreshToken issue(Long userId, Long tenantId, boolean superUser) {
        String token = generateToken();
        RefreshTokenPayload payload = new RefreshTokenPayload(userId, tenantId, superUser, Instant.now());
        Duration ttl = Duration.ofSeconds(properties.ttlSeconds());
        store.save(token, payload, ttl);
        store.addToUserIndex(userId, token, ttl);
        return new IssuedRefreshToken(token, payload);
    }

    /**
     * 用客户端提交的令牌换一张新的(轮换)。
     *
     * <p>三种结果对应三种处置:
     * <ul>
     *   <li>{@link RotationResult.Rotated}:正常。调用方接着签发新的访问令牌</li>
     *   <li>{@link RotationResult.Replayed}:**判定为重放**。调用方必须撤销该用户全部刷新令牌
     *       并踢掉全部会话(7.1.3),然后按 401 返回</li>
     *   <li>{@link RotationResult.Invalid}:凭据无效(过期、不存在、被撤销),调用方按 401 返回。
     *       注意与 Replayed 的区别:**无效不该踢人**(可能只是用户换了设备/久未登录),
     *       重放才要踢</li>
     * </ul>
     */
    public RotationResult rotate(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return new RotationResult.Invalid();
        }

        Optional<RefreshTokenPayload> current = store.load(presentedToken);
        if (current.isEmpty()) {
            // 有效令牌里找不到:可能是已用过(重放),也可能只是过期/被撤销
            return store.loadUsed(presentedToken)
                    .<RotationResult>map(RotationResult.Replayed::new)
                    .orElseGet(RotationResult.Invalid::new);
        }

        RefreshTokenPayload payload = current.get();
        // 一次性使用:先把旧令牌降级为"已用"(保留宽限期,用于识别重放),再从有效索引里摘掉
        store.markUsed(presentedToken, payload, Duration.ofSeconds(properties.reuseGraceSeconds()));
        store.delete(presentedToken);
        store.removeFromUserIndex(payload.userId(), presentedToken);

        IssuedRefreshToken rotated = issue(payload.userId(), payload.tenantId(), payload.superUser());
        return new RotationResult.Rotated(rotated.token(), rotated.payload());
    }

    /**
     * 撤销该用户的全部刷新令牌。改密、禁用用户、禁用租户时调用(7.1.3 的撤销时机表)。
     */
    public int revokeAll(Long userId) {
        return store.revokeAllOf(userId);
    }

    /**
     * 该用户当前有效的刷新令牌(只读,用于诊断与测试:比如客服排查"这个账号还有几个端在线")。
     * 不要用它做鉴权判断。
     */
    public Set<String> tokensOfUser(Long userId) {
        return store.tokensOf(userId);
    }

    /**
     * 撤销单张令牌(登出时用)。
     */
    public void revoke(String token) {
        store.load(token).ifPresent(payload -> {
            store.delete(token);
            store.removeFromUserIndex(payload.userId(), token);
        });
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 签发结果。 */
    public record IssuedRefreshToken(String token, RefreshTokenPayload payload) {
    }

    /** 轮换结果,三种情形见 {@link #rotate(String)} 的说明。 */
    public sealed interface RotationResult {

        record Rotated(String newToken, RefreshTokenPayload payload) implements RotationResult {
        }

        record Replayed(RefreshTokenPayload payload) implements RotationResult {
        }

        record Invalid() implements RotationResult {
        }
    }
}
