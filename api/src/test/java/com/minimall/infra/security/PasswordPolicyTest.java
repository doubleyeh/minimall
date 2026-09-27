package com.minimall.infra.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 密码策略的两个纯计算部分(架构文档 7.1.2)。
 *
 * <p>历史串的拼接与保留窗口、有效期判断都在这里,用真 bcrypt 算哈希(不用 mock):
 * "哈希比对"正是策略的核心,把它 mock 掉等于没测。
 */
@Tag("unit")
class PasswordPolicyTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final PasswordPolicy policy = new PasswordPolicy(encoder, new LoginProperties(
            5, 15, 10, 3, 120, 2, 30));

    @Test
    @DisplayName("改密记账:旧哈希进历史,最近一次在最前,超出窗口的丢掉")
    void keepsOnlyRecentHistory() {
        String h1 = encoder.encode("Pass@111");
        String h2 = encoder.encode("Pass@222");
        String h3 = encoder.encode("Pass@333");

        String afterFirst = policy.historyAfterChange(null, h1);
        String afterSecond = policy.historyAfterChange(afterFirst, h2);
        String afterThird = policy.historyAfterChange(afterSecond, h3);

        assertThat(afterFirst).isEqualTo(h1);
        assertThat(afterSecond).isEqualTo(h2 + "," + h1);
        assertThat(afterThird.split(",")).as("窗口是 2,最早的应被丢掉")
                .containsExactly(h3, h2);
    }

    @Test
    @DisplayName("历史不可复用:最近窗口内的密码不能再用")
    void rejectsPasswordInHistory() {
        String old = encoder.encode("Pass@111");

        assertThatThrownBy(() -> policy.ensureAcceptable(encoder.encode("Current@1"),
                old, "Pass@111", "Current@1"))
                .isInstanceOf(com.minimall.common.BusinessException.class)
                .hasMessageContaining("最近 2 次用过的密码");
    }

    @Test
    @DisplayName("与当前密码相同也要拒(提示要说人话)")
    void rejectsCurrentPassword() {
        String current = encoder.encode("Current@1");

        assertThatThrownBy(() -> policy.ensureAcceptable(current, null, "Current@1", "Current@1"))
                .isInstanceOf(com.minimall.common.BusinessException.class)
                .hasMessageContaining("新密码不能与原密码相同");
    }

    @Test
    @DisplayName("历史为空或含空段时不报错(老数据没有历史)")
    void toleratesEmptyHistory() {
        policy.ensureAcceptable(encoder.encode("Current@1"), "", "Fresh@123", "Current@1");
        policy.ensureAcceptable(encoder.encode("Current@1"), " , ", "Fresh@123", "Current@1");

        assertThat(policy.historyAfterChange(",,", encoder.encode("x"))).isNotBlank();
    }

    @Test
    @DisplayName("有效期:0 表示不启用,任何时间都不过期")
    void expireDaysZeroDisablesPolicy() {
        PasswordPolicy disabled = new PasswordPolicy(encoder, new LoginProperties(
                5, 15, 10, 3, 120, 5, 0));

        assertThat(disabled.isExpired(null)).isFalse();
        assertThat(disabled.isExpired(LocalDateTime.now().minusYears(10))).isFalse();
    }

    @Test
    @DisplayName("有效期:超过天数算过期,没到不算;从没改过密的按过期处理")
    void expireCheck() {
        assertThat(policy.isExpired(LocalDateTime.now().minusDays(31))).isTrue();
        assertThat(policy.isExpired(LocalDateTime.now().minusDays(29))).isFalse();
        assertThat(policy.isExpired(null))
                .as("开了有效期却不认老数据,等于给它们开了后门")
                .isTrue();
    }
}
