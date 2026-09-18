package com.minimall.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志脱敏的单元测试(架构文档 7.2)。
 *
 * <p>为什么这条用例值得单写:操作日志是**明文落库**的,脱敏是唯一的防线,
 * 而它的失效不会有任何报错——只有"密码出现在日志表里"这一个后果。
 * 这里不依赖 DB/Redis,所以它会在每次 {@code mvn test} 里跑。
 */
class MaskingTest {

    @Test
    @DisplayName("字典型参数里的密码/令牌/密钥被掩码")
    void masksSensitiveKeysInMapLikeText() {
        String params = "{username=admin, password=admin123, token=abc.def.ghi, secret=s3cr3t}";

        String masked = Masking.maskSensitiveText(params);

        assertThat(masked).doesNotContain("admin123").doesNotContain("abc.def.ghi").doesNotContain("s3cr3t");
        assertThat(masked).contains("username=admin");
        assertThat(masked).contains("password=***");
    }

    @Test
    @DisplayName("record 的 toString 形态同样会被掩码(login 请求体的真实形态)")
    void masksRecordToString() {
        // 登录请求的 toString 长这样:LoginRequest[tenantCode=acme, username=admin, password=admin123, deviceId=...]
        String params = "LoginRequest[tenantCode=acme, username=admin, password=admin123, deviceId=web-1]";

        String masked = Masking.maskSensitiveText(params);

        assertThat(masked).doesNotContain("admin123");
        assertThat(masked).contains("password=***");
        assertThat(masked).contains("tenantCode=acme");
    }

    @Test
    @DisplayName("改密接口的新旧密码都被掩码")
    void masksBothOldAndNewPassword() {
        String params = "ChangePasswordRequest[oldPassword=old12345, newPassword=new12345]";

        String masked = Masking.maskSensitiveText(params);

        assertThat(masked).doesNotContain("old12345").doesNotContain("new12345");
    }

    @Test
    @DisplayName("手机号保留前3后4,中间打码")
    void masksPhoneKeepingHeadAndTail() {
        String masked = Masking.maskSensitiveText("UserSaveRequest[username=u1, phone=13800138000]");

        assertThat(masked).contains("138****8000");
        assertThat(masked).doesNotContain("13800138000");
    }

    @Test
    @DisplayName("不碰无关内容:普通参数原样保留")
    void keepsNonSensitiveContent() {
        String params = "RoleMenuGrantRequest[menuIds=[11, 12, 13]]";

        assertThat(Masking.maskSensitiveText(params)).isEqualTo(params);
    }

    @Test
    @DisplayName("超长文本被截断并标注原长度")
    void truncatesLongText() {
        String text = "x".repeat(5000);

        String truncated = Masking.truncate(text, 4000);

        assertThat(truncated).hasSizeLessThan(text.length());
        assertThat(truncated).startsWith("x".repeat(100));
        assertThat(truncated).contains("truncated");
        assertThat(truncated).contains("5000");
    }

    @Test
    @DisplayName("空值与未超长文本原样返回,不抛异常")
    void handlesEmptyAndShortText() {
        assertThat(Masking.maskSensitiveText(null)).isNull();
        assertThat(Masking.maskSensitiveText("")).isEmpty();
        assertThat(Masking.truncate("short", 4000)).isEqualTo("short");
        assertThat(Masking.truncate(null, 4000)).isNull();
    }

    @Test
    @DisplayName("phone 字段脱敏与 maskPhone 的单值脱敏结果一致")
    void phoneMaskingIsConsistentWithSingleValueVersion() {
        assertThat(Masking.maskSensitiveText("phone=13800138000")).contains(Masking.maskPhone("13800138000"));
    }
}
