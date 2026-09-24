package com.minimall.common;

/**
 * 错误码(架构文档 7.3:同类异常的码值集中定义在一处,不在 Controller 里散落魔法数字)。
 *
 * <p>码段约定:0 成功;4xxxx 客户端/业务可预期问题;5xxxx 服务端问题。
 */
public enum ErrorCode {

    OK(0, "ok"),

    /** 登录接口统一文案:租户不存在/被禁用/已过期/用户不存在/密码错误,一律用它,避免被用来探测租户与账号 */
    LOGIN_FAILED(40001, "用户名或密码错误"),
    ACCOUNT_LOCKED(40002, "账号已锁定,请稍后重试"),
    PARAM_INVALID(40003, "参数校验失败"),
    /** 失败次数到阈值后必须带验证码,但请求里没有(前端据此把验证码输入框亮出来) */
    CAPTCHA_REQUIRED(40004, "请先完成验证码"),
    CAPTCHA_INVALID(40005, "验证码不正确或已失效"),

    UNAUTHORIZED(40100, "未登录或登录已过期"),
    TENANT_ABNORMAL(40101, "租户状态异常,请联系管理员"),
    IP_RATE_LIMITED(42900, "请求过于频繁,请稍后重试"),

    FORBIDDEN(40300, "无操作权限"),
    PASSWORD_CHANGE_REQUIRED(40301, "请先修改初始密码"),
    /** 授权越界:提交的菜单不在该租户套餐范围内(架构文档 5.2.1) */
    MENU_OUT_OF_PACKAGE(40302, "存在不在当前套餐范围内的菜单,授权已拒绝"),

    NOT_FOUND(40400, "资源不存在"),

    BUSINESS_ERROR(50000, "业务处理失败"),
    DATA_CONFLICT(50002, "数据已存在或存在引用关系"),
    SYSTEM_ERROR(50001, "系统异常,请稍后重试"),

    /** 该租户没配微信支付,或全局的回调地址前缀没配 */
    PAY_CHANNEL_NOT_CONFIGURED(50003, "支付渠道未配置"),
    /** 调微信下单/退款失败:网络异常或微信返回业务错误 */
    PAY_CHANNEL_ERROR(50004, "支付渠道调用失败"),
    /** 回调验签不过、序列号不匹配或时间戳超窗 */
    PAY_SIGNATURE_INVALID(50005, "回调签名校验失败"),
    /** 金额非正数,或元转分时溢出 */
    PAY_AMOUNT_INVALID(50006, "支付金额不合法"),
    /** 回调报文结构不对或解密失败 */
    WX_PAY_NOTIFY_INVALID(50007, "支付回调报文不合法");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
