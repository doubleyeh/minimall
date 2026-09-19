package com.minimall.mall.infra.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * 客户端令牌的签发与校验(商城设计文档 3.1)。
 *
 * <p><b>为什么用 JWT 而不是服务端会话</b>:客户端要求"30 天免登录",但小程序里用户随时可能
 * 长时间不打开 —— 服务端会话意味着 30 天内每个客户的会话都要占存储,而且过期就得重新登录。
 * JWT 把身份放在令牌里,服务端不需要存储,过期后前端静默重登(3.1)。
 *
 * <p><b>代价必须清楚</b>:JWT 签发后在过期前无法撤销(除非引入黑名单)。所以:
 * <ul>
 *   <li>客户被禁用时,靠"每次请求校验客户状态"来兜底(见 {@code ClientAuthFilter} 之后的业务校验),
 *       不能只依赖令牌有效</li>
 *   <li>{@code ttlDays} 不能配得过长;要"强制下线"就得加黑名单表(3.10 的开放项 4 已记录)</li>
 * </ul>
 */
@Component
public class ClientTokenService {

    private static final Logger log = LoggerFactory.getLogger(ClientTokenService.class);

    /** HS256 要求密钥至少 256 位(32 字节)。短于这个长度 jjwt 会直接拒绝,不如在启动时就说清楚。 */
    private static final int MIN_SECRET_BYTES = 32;

    private static final String CLAIM_TENANT_ID = "tenantId";

    private final SecretKey key;
    private final ClientTokenProperties properties;

    public ClientTokenService(ClientTokenProperties properties) {
        String secret = properties.jwtSecret();
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            // 启动即失败,而不是等到第一次登录才报错:密钥配错属于部署事故,越早暴露越好
            throw new IllegalStateException("mall.auth.jwt-secret 至少需要 " + MIN_SECRET_BYTES
                    + " 字节(HS256 要求 256 位),当前长度为 " + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.properties = properties;
    }

    public String issue(Long tenantId, Long customerId) {
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofDays(properties.ttlDaysOrDefault()));
        return Jwts.builder()
                .subject(String.valueOf(customerId))
                .claim(CLAIM_TENANT_ID, tenantId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析令牌。任何问题(签名不对、过期、格式错、缺少 claim)统一返回空 ——
     * 调用方只需要知道"这个令牌不可用",不需要区分原因;把原因返回给端上反而会泄露实现细节。
     */
    public Optional<ClientPrincipal> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            Long customerId = Long.valueOf(claims.getSubject());
            Object tenantId = claims.get(CLAIM_TENANT_ID);
            if (tenantId == null) {
                return Optional.empty();
            }
            return Optional.of(new ClientPrincipal(Long.valueOf(tenantId.toString()), customerId));
        } catch (JwtException | IllegalArgumentException ex) {
            if (log.isDebugEnabled()) {
                log.debug("客户端令牌校验失败: {}", ex.getMessage());
            }
            return Optional.empty();
        }
    }

    /** 令牌有效期(秒),登录响应里返回给前端,便于它提前静默重登。 */
    public long ttlSeconds() {
        return Duration.ofDays(properties.ttlDaysOrDefault()).toSeconds();
    }
}
