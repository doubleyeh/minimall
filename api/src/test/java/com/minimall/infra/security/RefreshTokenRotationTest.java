package com.minimall.infra.security;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 刷新令牌的轮换与重放检测(架构文档 7.1.3、8.1 用例 20)。
 *
 * <p>这是认证链路里最"安全敏感"的一段逻辑,而且它**不依赖 DB/HTTP**,只依赖 Redis,
 * 所以这里直接手工装配组件连本机 Redis 验证:比走完整 Spring 上下文更快,失败信息也更直接。
 *
 * <p>为什么必须单独测"重放"这一条:轮换写错成"旧令牌也能继续用"时,功能测试(能刷新、能访问)
 * 全部会通过,只有安全测试会把问题暴露出来——而它正是这个机制存在的理由。
 */
@Tag("integration")
class RefreshTokenRotationTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private static RefreshTokenService service;

    @BeforeAll
    static void setUp() {
        // 依赖不可用时**显式跳过**,而不是抛一堆连接异常:这样本机没起 Redis 也能跑其余用例,
        // 而报告里的 Skipped 数字会把这层信息保留下来(CI 上 Redis 一定在,用例照常执行)。
        boolean redisAvailable;
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 6379), 500);
            redisAvailable = true;
        } catch (Exception ex) {
            redisAvailable = false;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(redisAvailable,
                "本机 Redis(127.0.0.1:6379)不可用,跳过刷新令牌轮换与重放用例");

        connectionFactory = new LettuceConnectionFactory("127.0.0.1", 6379);
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();

        // TTL 用短值即可:本用例只验证轮换与重放判定,不验证过期时间本身
        service = new RefreshTokenService(new RefreshTokenStore(redis), new RefreshTokenProperties(300, 60));
    }

    @AfterAll
    static void tearDown() {
        // 必须判空:@BeforeAll 可能因为 Redis 不可用而中止(assumption),那时这两个字段还是 null
        if (redis != null) {
            Set<String> keys = redis.keys("auth:rt:*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("签发后能正常轮换:新令牌与旧令牌不同,且新令牌可以继续轮换")
    void rotateIssuesNewTokenAndKeepsWorking() {
        RefreshTokenService.IssuedRefreshToken issued = service.issue(7001L, 101L, false);

        RefreshTokenService.RotationResult first = service.rotate(issued.token());

        assertThat(first).isInstanceOf(RefreshTokenService.RotationResult.Rotated.class);
        String rotatedToken = ((RefreshTokenService.RotationResult.Rotated) first).newToken();
        assertThat(rotatedToken).isNotEqualTo(issued.token());

        // 新令牌是有效的(载荷里的身份保持)
        RefreshTokenService.RotationResult second = service.rotate(rotatedToken);
        assertThat(second).isInstanceOf(RefreshTokenService.RotationResult.Rotated.class);
        assertThat(((RefreshTokenService.RotationResult.Rotated) second).payload().userId()).isEqualTo(7001L);
        assertThat(((RefreshTokenService.RotationResult.Rotated) second).payload().tenantId()).isEqualTo(101L);
    }

    @Test
    @DisplayName("宽限期内重复使用同一张旧令牌 → 判定为重放(调用方据此踢掉该用户全部会话)")
    void reusingRotatedTokenIsDetectedAsReplay() {
        RefreshTokenService.IssuedRefreshToken issued = service.issue(7002L, 102L, false);
        service.rotate(issued.token());

        RefreshTokenService.RotationResult replay = service.rotate(issued.token());

        assertThat(replay).isInstanceOf(RefreshTokenService.RotationResult.Replayed.class);
        // 必须能从重放结果里拿到身份,否则调用方不知道该撤销谁
        assertThat(((RefreshTokenService.RotationResult.Replayed) replay).payload().userId()).isEqualTo(7002L);
    }

    @Test
    @DisplayName("不存在的令牌 → Invalid(不能当成重放,否则会用一次乱输的令牌把正常用户踢下线)")
    void unknownTokenIsInvalidNotReplay() {
        assertThat(service.rotate("not-a-real-token"))
                .isInstanceOf(RefreshTokenService.RotationResult.Invalid.class);
        assertThat(service.rotate(""))
                .isInstanceOf(RefreshTokenService.RotationResult.Invalid.class);
        assertThat(service.rotate(null))
                .isInstanceOf(RefreshTokenService.RotationResult.Invalid.class);
    }

    @Test
    @DisplayName("revokeAll 之后该用户所有刷新令牌都不可用(改密/禁用用户/禁用租户依赖这条)")
    void revokeAllInvalidatesEveryTokenOfTheUser() {
        RefreshTokenService.IssuedRefreshToken first = service.issue(7003L, 103L, false);
        RefreshTokenService.IssuedRefreshToken second = service.issue(7003L, 103L, false);

        int revoked = service.revokeAll(7003L);

        assertThat(revoked).isEqualTo(2);
        assertThat(service.rotate(first.token())).isInstanceOf(RefreshTokenService.RotationResult.Invalid.class);
        assertThat(service.rotate(second.token())).isInstanceOf(RefreshTokenService.RotationResult.Invalid.class);
        assertThat(service.tokensOfUser(7003L)).isEmpty();
    }

    @Test
    @DisplayName("多个端互不影响:撤销其中一个端的令牌,另一个端仍可刷新")
    void revokingOneTokenDoesNotAffectOtherDevices() {
        RefreshTokenService.IssuedRefreshToken deviceA = service.issue(7004L, 104L, false);
        RefreshTokenService.IssuedRefreshToken deviceB = service.issue(7004L, 104L, false);

        service.revoke(deviceA.token());

        assertThat(service.rotate(deviceA.token())).isInstanceOf(RefreshTokenService.RotationResult.Invalid.class);
        assertThat(service.rotate(deviceB.token())).isInstanceOf(RefreshTokenService.RotationResult.Rotated.class);
    }
}
