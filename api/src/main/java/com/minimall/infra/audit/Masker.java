package com.minimall.infra.audit;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 日志脱敏(架构文档 7.2)。
 *
 * <p><b>为什么用正则而不是反序列化后逐字段处理</b>:①切面拿到的可能是 DTO 的 {@code toString()},
 * 也可能是 Map 或字符串,正则对这三者都有效;②不引入序列化依赖,也就不存在"某个类型序列化失败
 * 导致日志写入整体失败"的连带故障。
 *
 * <p>**重点场景**:登录接口的请求体里有明文密码。切面必须在写入前调用本类,
 * 否则密码会原样进日志表 —— 这是最容易被忽略、后果最直接的一处泄露。
 *
 * <p>规则来自 {@link MaskingProperties}(可配置),不在代码里写死字段名单。
 */
@Component
public class Masker {

    private final Pattern sensitiveField;
    private final Pattern phoneField;

    public Masker(MaskingProperties properties) {
        this.sensitiveField = Pattern.compile(
                "(?i)\\b(" + alternatives(properties.sensitiveFields())
                        + ")(\\s*[=:]\\s*)(\"?)([^,\")\\s\\]]+)");
        this.phoneField = Pattern.compile(
                "(?i)\\b(" + alternatives(properties.phoneFields())
                        + ")(\\s*[=:]\\s*)(\"?)(\\d{3})\\d{4}(\\d{4})");
    }

    /** 整体掩码敏感字段、按"保留前三后四"掩码手机号;null 与空串原样返回。 */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = sensitiveField.matcher(text).replaceAll("$1$2$3***");
        return phoneField.matcher(masked).replaceAll("$1$2$3$4****$5");
    }

    /**
     * 把字段名拼成正则的"或"分支。
     *
     * <p>用 {@link Pattern#quote} 逐个转义:配置里写错一个字符(比如多写了个 {@code (})不该让应用
     * 连启动都起不来,更不该变成一条能匹配任意内容的正则把日志全掩掉。
     */
    private String alternatives(java.util.List<String> fields) {
        return fields.stream().map(Pattern::quote).collect(Collectors.joining("|"));
    }
}
