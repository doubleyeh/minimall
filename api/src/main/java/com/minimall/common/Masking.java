package com.minimall.common;


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
