package com.minimall.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单值脱敏与截断的单元测试(架构文档 7.2)。
 *
 * <p>文本级的字段脱敏(整个请求体那种)已经移到 {@code Masker},那里规则可配置;
 * 这里剩下的两件事与配置无关:手机号这种"已知是手机号"的单值处理,以及文本截断。
 */
class MaskingTest {

    @Test
    @DisplayName("手机号保留前3后4,中间打码")
    void masksPhoneKeepingHeadAndTail() {
        assertThat(Masking.maskPhone("13800138000")).isEqualTo("138****8000");
        assertThat(Masking.maskPhone(null)).isNull();
        assertThat(Masking.maskPhone("  ")).isEqualTo("  ");
        assertThat(Masking.maskPhone("123")).as("短到没法保留前后时整段打码").isEqualTo("***");
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
        assertThat(Masking.truncate("short", 4000)).isEqualTo("short");
        assertThat(Masking.truncate(null, 4000)).isNull();
    }
}
