package com.minimall.infra.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路 traceId(架构文档 7.2)。
 *
 * <p>三件事:
 * <ol>
 *   <li>生成 traceId(请求头已带 {@code X-Trace-Id} 时复用它,例如来自网关)</li>
 *   <li>写进 MDC,让日志里自动带上;回写响应头,方便把前端报错和后端日志对上</li>
 *   <li>请求结束清理 MDC —— **必须清**,否则线程池复用会让下一个请求串上上一个的 traceId</li>
 * </ol>
 *
 * <p>它必须在 {@code TenantWebFilter} 之前执行,这样租户识别阶段的日志也带 traceId。
 * 跨线程不自动传播(虚拟线程/线程池都如此),需要保留 traceId 的异步任务
 * 由调用方把值传过去或自定义 {@code TaskDecorator} 拷贝 MDC(见 6.3)。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(TRACE_ID_MDC_KEY, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }
}
