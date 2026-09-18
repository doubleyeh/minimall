package com.minimall.common;

import java.util.regex.Pattern;

/**
 * 敏感字段脱敏(架构文档 7.1)。
 *
 * <p><b>必须在服务端脱敏,不能交给前端</b>:交给前端等于明文仍然出了服务器,
 * 抓包/日志/浏览器插件都能拿到,脱敏就失去意义了。
 */
public final class Masking {

    private Masking() {
    }

    /**
     * 手机号脱敏:保留前 3 位与后 4 位,中间用 4 个星号(如 {@code 138****8000})。
     *
     * <p>长度不足或为空时返回原值/空串,不抛异常:脱敏是输出环节的加固,
     * 不该因为一条脏数据把整个查询接口打成 500。
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return phone;
        }
        if (phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 敏感键名的黑名单(架构文档 7.2)。
     *
     * <p>**这是一份必须维护的清单**:新增任何"名字里带这些词"的字段都会被自动掩码,
     * 反之如果某个敏感字段起了别的名字(如 {@code pwd}、{@code authCode}),必须在这里补上,
     * 否则会明文落进日志表。
     */
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(
            "(?i)\\b(password|oldPassword|newPassword|confirmPassword|pwd|token|accessToken|refreshToken"
                    + "|secret|appSecret|privateKey|idCard|idNumber|bankCard)(\\s*[=:]\\s*)(\"?)([^,\")\\s\\]]+)");

    private static final Pattern PHONE_FIELD = Pattern.compile(
            "(?i)\\b(phone|mobile|mobilePhone|telephone)(\\s*[=:]\\s*)(\"?)(\\d{3})\\d{4}(\\d{4})");

    /**
     * 对"即将写进日志的文本"做脱敏(架构文档 7.2 的硬要求)。
     *
     * <p>为什么用正则而不是先序列化成 JSON 再按 key 处理:①日志切面拿到的可能是 DTO 的
     * {@code toString()}(record 的形状是 {@code XxxRequest[password=abc]},字段名依然可见),
     * 也可能是 Map 或字符串,正则对这三者都有效;②不引入序列化依赖,也就不存在
     * "某个类型序列化失败导致日志写入整体失败"的连带故障。
     *
     * <p>**重点场景**:登录接口的请求体里有明文密码。切面必须在写入前调用本方法,
     * 否则密码会原样进日志表——这是最容易被忽略、后果最直接的一处泄露。
     */
    public static String maskSensitiveText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = SENSITIVE_FIELD.matcher(text).replaceAll("$1$2$3***");
        return PHONE_FIELD.matcher(masked).replaceAll("$1$2$3$4****$5");
    }

    /**
     * 截断到指定长度。用于 {@code request_params}/{@code error_msg}
     * ——大报文(文件上传、批量导入)不截断会把日志表撑爆,而日志的价值本来也不在那部分内容里。
     */
    public static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...(truncated,原长度 " + text.length() + ")";
    }
}
