package com.minimall.infra.audit;

import com.minimall.common.Masking;
import com.minimall.infra.web.TraceIdFilter;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 操作日志切面(架构文档 7.2)。
 *
 * <p>职责很单一:把"谁在什么时候、对哪个模块、做了什么、结果如何"**脱敏后**交给异步写入器。
 * 它刻意不碰事务、不碰 DB——落库在 {@link OperLogWriter} 的独立线程里完成,
 * 这样"日志"这个非功能需求不会把业务事务拖长,也不会因为日志失败回滚业务。
 *
 * <p><b>快照必须在主线程捕获</b>(这是最容易写错的一处):请求参数、审计上下文、MDC 里的 traceId
 * 都只在当前请求线程里有值,一旦把 {@code joinPoint} 或原始 DTO 引用交给异步线程,
 * 拿到的可能是被复用/已清空的状态。所以这里在 {@code proceed()} 之前就把它们转成不可变的字符串。
 *
 * <p>顺序 {@code @Order(20)}:在租户过滤切面(10)之后,保证日志记录发生在过滤器已启用的上下文里
 * (这样日志里的 tenantId 已经是识别后的值)。它不参与事务顺序,因为日志写库是独立线程。
 */
@Aspect
@Component
@Order(20)
public class OperLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperLogAspect.class);

    /** 与建表脚本的列长度/约定一致:参数与错误信息都会截断到这个长度上限。 */
    private static final int MAX_TEXT_LENGTH = 4000;
    private static final int MAX_METHOD_LENGTH = 255;
    private static final int SUCCESS = 1;
    private static final int FAILURE = 0;

    private final OperLogWriter writer;

    public OperLogAspect(OperLogWriter writer) {
        this.writer = writer;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        // —— 以下三行必须在主线程执行(见类注释)——
        AuditContext audit = AuditContext.current();
        String traceId = MDC.get(TraceIdFilter.TRACE_ID_MDC_KEY);
        String requestParams = auditLog.recordParams() ? describeArgs(joinPoint) : null;
        String method = describeMethod(joinPoint);

        try {
            Object result = joinPoint.proceed();
            // 成功分支**重新取一次**审计上下文:登录这类接口是在方法内部才确定身份的(7.1.1),
            // 入口处捕获的快照里 tenantId/userId 还是空的。取到就用,取不到退回入口快照
            AuditContext after = AuditContext.current();
            Long tenantId = after.tenantId() != null ? after.tenantId() : audit.tenantId();
            Long userId = after.userId() != null ? after.userId() : audit.userId();
            writer.write(new OperLogEntry(tenantId, userId, auditLog.module(), auditLog.permCode(),
                    method, requestParams, SUCCESS, null, audit.ip(), traceId, LocalDateTime.now()));
            return result;
        } catch (Throwable ex) {
            // 失败也要记,而且要把错误信息带上——"哪些操作在报错"正是操作日志最常用的用法
            writer.write(new OperLogEntry(audit.tenantId(), audit.userId(), auditLog.module(), auditLog.permCode(),
                    method, requestParams, FAILURE,
                    Masking.truncate(Masking.maskSensitiveText(describeError(ex)), MAX_TEXT_LENGTH),
                    audit.ip(), traceId, LocalDateTime.now()));
            throw ex;
        }
    }

    /**
     * 把方法入参拼成可读文本。过滤掉 Servlet 对象(它们 toString 出来是没有意义的地址串),
     * 并立刻做脱敏与截断。
     */
    private String describeArgs(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return null;
        }
        String text = Arrays.stream(args)
                .filter(Objects::nonNull)
                .filter(arg -> !(arg instanceof ServletRequest) && !(arg instanceof ServletResponse))
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        if (text.isEmpty()) {
            return null;
        }
        // 脱敏必须在主线程完成:交给异步线程意味着敏感内容会多存活一段时间,而且那时拼串成本也在异步侧
        return Masking.truncate(Masking.maskSensitiveText(text), MAX_TEXT_LENGTH);
    }

    private String describeMethod(ProceedingJoinPoint joinPoint) {
        String method = joinPoint.getSignature().toShortString();
        return Masking.truncate(method, MAX_METHOD_LENGTH);
    }

    private String describeError(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return ex.getClass().getSimpleName() + ": " + message;
    }
}
