package com.minimall.common;

import cn.dev33.satoken.exception.DisableServiceException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理(架构文档 7.2):区分业务异常 / 系统异常 / 参数校验异常,统一响应码规范。
 *
 * <p>约定:
 * <ul>
 *   <li>业务异常:按 ErrorCode 原样返回,不打印堆栈(是预期内的失败,打堆栈只会淹没真问题)</li>
 *   <li>参数校验异常:返回 40003,message 里带上具体字段,便于前端直接提示</li>
 *   <li>唯一约束冲突:数据库唯一索引是最后一道且可靠的防线(架构文档 5.1/9.7),
 *       这里翻译成 50002,而不是把 SQL 异常原样抛给前端</li>
 *   <li>其他异常:50001 兜底,必须打 ERROR 堆栈——这类才是需要排查的</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常:默认用 HTTP 200 + 业务码承载(7.3),**但"未登录 / 无权限 / 限流"这三类必须带上真正的状态码**。
     *
     * <p>理由与下面 Sa-Token 那两组一致:前端与网关最先看到的是状态码。"登录态不可用"要靠 401 分流到登录页、
     * "无权限"要靠 403 提示、限流要靠 429(前端 3.4 明确要求 429 时提示"操作过于频繁"且禁止自动重试,
     * 7.1.1 也写了"超限直接 429")。之前统一返回 200 时,这些响应在网关上与成功请求无法区分。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        if (log.isDebugEnabled()) {
            log.debug("业务异常 uri={} code={} message={}", request.getRequestURI(), errorCode.code(), ex.getMessage());
        }
        HttpStatus status = switch (errorCode) {
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case IP_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(errorCode.code(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return ApiResponse.fail(ErrorCode.PARAM_INVALID.code(), detail.isEmpty() ? ErrorCode.PARAM_INVALID.message() : detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException ex) {
        log.debug("请求体解析失败:{}", ex.getMessage());
        return ApiResponse.fail(ErrorCode.PARAM_INVALID.code(), "请求体格式不正确");
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ApiResponse<Void> handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("唯一约束冲突:{}", ex.getMostSpecificCause().getMessage());
        return ApiResponse.fail(ErrorCode.DATA_CONFLICT.code(), "数据已存在,请检查唯一字段");
    }

    /**
     * 数据完整性约束冲突(唯一键、外键、非空)。
     *
     * <p><b>为什么必须单独处理</b>:唯一键冲突按触发时机不同会抛出**两种**异常 ——
     * 仓储方法执行时就撞上 → Spring 转成 {@link DuplicateKeyException};
     * Hibernate 把插入推迟到事务提交时才 flush → 抛的是 {@link DataIntegrityViolationException},
     * 而它正是 {@code DuplicateKeyException} 的**父类**。只处理子类就漏掉了后一种。
     *
     * <p>漏掉的表现:管理员填了一个已存在的手机号,收到的是"系统异常,请稍后重试"——
     * 于是他会反复重试一个永远不会成功的请求,而日志里多一条 ERROR 堆栈。
     * 它本该是 50002(数据已存在或存在引用关系),前端据此提示到具体字段。
     *
     * <p>这个洞是靠一条真实 HTTP 用例发现的:用同一个手机号建第二个用户。
     * 单元/服务层用例发现不了 —— 那里的事务边界与真实请求不同,异常类型也就不一样。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ApiResponse<Void> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("数据完整性约束冲突:{}", ex.getMostSpecificCause().getMessage());
        return ApiResponse.fail(ErrorCode.DATA_CONFLICT);
    }

    /**
     * Sa-Token 的鉴权异常(架构文档 5.2、7.3)。
     *
     * <p>必须显式翻译成统一响应体:Sa-Token 抛的是自己的异常类型,不处理的话会被下面的
     * {@code Exception} 兜底成 500 —— 前端会把"没登录"当成系统故障,而 401/403 的分流
     * (跳登录页 vs 提示无权限)也就做不出来了。
     *
     * <p>**HTTP 状态码必须一起设置**({@code @ResponseStatus}):只改响应体里的 code 是不够的 ——
     * 前端与网关最先看到的是状态码,而"通过/403"也是 8.2 权限矩阵用例的断言口径。
     * 不设的话这些拒绝会以 200 返回,从监控上看与成功请求无法区分(实测踩过)。
     * 状态码与过滤器里的拒绝(见 TenantWebFilter)保持一致:401=未登录、403=已登录但无权限。
     */
    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleNotLogin(NotLoginException ex) {
        return ApiResponse.fail(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler({NotPermissionException.class, NotRoleException.class, DisableServiceException.class})
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ApiResponse<Void> handleNotAllowed(Exception ex) {
        log.debug("鉴权拒绝:{}", ex.getMessage());
        return ApiResponse.fail(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("系统异常 uri={}", request.getRequestURI(), ex);
        return ApiResponse.fail(ErrorCode.SYSTEM_ERROR);
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
