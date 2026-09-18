package com.minimall.common;

/**
 * 业务异常:可预期的失败(校验不通过、状态不允许、越权等),由全局异常处理器转成统一响应体。
 *
 * <p>不要用它表达系统级错误(那用原始异常,由 50001 兜底),否则日志里会丢失堆栈线索。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
