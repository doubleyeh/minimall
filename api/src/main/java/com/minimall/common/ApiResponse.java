package com.minimall.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应体(架构文档 7.3):{code, message, data}。
 *
 * <p>code 为 0 表示成功;非 0 时 data 为 null(不输出该字段,避免前端拿到 null 又要判空)。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.message(), data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.message(), null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.code(), errorCode.message(), null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
