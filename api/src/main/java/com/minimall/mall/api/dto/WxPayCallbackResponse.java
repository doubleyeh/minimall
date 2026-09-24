package com.minimall.mall.api.dto;

/**
 * 微信支付回调的应答体。
 *
 * <p>必须用微信定义的这套字段:项目自己的 {@code {code:0}} 微信不认,会一直重推回调。
 */
public record WxPayCallbackResponse(String code, String message) {

    public static final String SUCCESS_CODE = "SUCCESS";
    public static final String FAIL_CODE = "FAIL";

    public static WxPayCallbackResponse success() {
        return new WxPayCallbackResponse(SUCCESS_CODE, "成功");
    }

    public static WxPayCallbackResponse fail(String message) {
        return new WxPayCallbackResponse(FAIL_CODE, message == null ? "失败" : message);
    }
}
