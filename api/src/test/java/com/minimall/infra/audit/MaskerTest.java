package com.minimall.infra.audit;

import com.minimall.common.Masking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志脱敏的规则(架构文档 7.2)。
 *
 * <p>为什么这条用例值得单写:操作日志是**明文落库**的,脱敏是唯一的防线,
 * 而它失效不会有任何报错 —— 只有一个后果:密码出现在日志表里。
 *
 * <p>规则现在来自配置,所以这里既验默认名单,也验"加一个字段就生效"与"配置写错不会误伤"。
 */
@Tag("unit")
class MaskerTest {

    private final Masker masker = new Masker(new MaskingProperties(null, null));

    @Test
    @DisplayName("字典型参数里的密码/令牌/密钥被掩码")
    void masksSensitiveKeysInMapLikeText() {
        String params = "{username=admin, password=admin123, token=abc.def.ghi, secret=s3cr3t}";

        String masked = masker.mask(params);

        assertThat(masked).doesNotContain("admin123").doesNotContain("abc.def.ghi").doesNotContain("s3cr3t");
        assertThat(masked).contains("username=admin");
        assertThat(masked).contains("password=***");
    }

    @Test
    @DisplayName("record 的 toString 形态同样会被掩码(login 请求体的真实形态)")
    void masksRecordToString() {
        String params = "LoginRequest[tenantCode=acme, username=admin, password=admin123, deviceId=web-1]";

        String masked = masker.mask(params);

        assertThat(masked).doesNotContain("admin123");
        assertThat(masked).contains("password=***");
        assertThat(masked).contains("tenantCode=acme");
    }

    @Test
    @DisplayName("改密接口的新旧密码都被掩码")
    void masksBothOldAndNewPassword() {
        String masked = masker.mask("ChangePasswordRequest[oldPassword=old12345, newPassword=new12345]");

        assertThat(masked).doesNotContain("old12345").doesNotContain("new12345");
    }

    @Test
    @DisplayName("手机号保留前3后4,中间打码")
    void masksPhoneKeepingHeadAndTail() {
        String masked = masker.mask("UserSaveRequest[username=u1, phone=13800138000]");

        assertThat(masked).contains("138****8000");
        assertThat(masked).doesNotContain("13800138000");
    }

    @Test
    @DisplayName("不碰无关内容:普通参数原样保留")
    void keepsNonSensitiveContent() {
        String params = "RoleMenuGrantRequest[menuIds=[11, 12, 13]]";

        assertThat(masker.mask(params)).isEqualTo(params);
    }

    @Test
    @DisplayName("空值原样返回,不抛异常")
    void handlesEmptyText() {
        assertThat(masker.mask(null)).isNull();
        assertThat(masker.mask("")).isEmpty();
    }

    @Test
    @DisplayName("phone 字段脱敏与 maskPhone 的单值脱敏结果一致")
    void phoneMaskingIsConsistentWithSingleValueVersion() {
        assertThat(masker.mask("phone=13800138000")).contains(Masking.maskPhone("13800138000"));
    }

    @Test
    @DisplayName("配置里加一个字段就能生效,不用改代码")
    void respectsConfiguredFields() {
        Masker configured = new Masker(new MaskingProperties(
                List.of("password", "wxAppSecret"), List.of("phone")));

        String masked = configured.mask("{wxAppSecret=very-secret-value, userName=u1}");

        assertThat(masked).doesNotContain("very-secret-value");
        assertThat(masked).contains("userName=u1");
    }

    @Test
    @DisplayName("字段名里带正则元字符也不会误伤:逐个转义后再拼")
    void quotesConfiguredFieldNames() {
        // 配置写错(比如多写了个括号)不该变成一条能匹配任意内容的正则,把日志整段掩掉
        Masker weird = new Masker(new MaskingProperties(List.of("weird(field"), List.of("phone")));

        assertThat(weird.mask("normalParam=keepme")).isEqualTo("normalParam=keepme");
        assertThat(weird.mask("weird(field=hide")).doesNotContain("hide");
    }
}
