package com.minimall.infra.audit;

import java.time.LocalDateTime;

/**
 * 操作日志的"待落库快照"(架构文档 4.12、7.2)。
 *
 * <p>为什么不在异步线程里直接造 {@code SysOperLog} 实体、也不传原始请求对象:
 * <ul>
 *   <li>**跨线程传递的是快照,不是引用**。请求参数对象在方法返回后可能被复用或修改,
 *       把引用交给异步线程会让日志内容变得不可预测;这里在提交任务前就把它序列化成字符串</li>
 *   <li>身份信息({@code tenantId}/{@code userId}/{@code ip}/{@code traceId})同样在提交前捕获,
 *       因为异步线程里 {@code TenantContext}、{@code AuditContext}、MDC 全都是空的(见 4.12、6.3)</li>
 * </ul>
 *
 * @param tenantId      租户 ID,**允许为空**(登录失败等场景还没识别出租户,而那条日志恰恰最有排查价值)
 * @param userId        操作人 ID,允许为空(同上)
 * @param status        0-失败 1-成功
 * @param requestParams 已脱敏、已截断的参数文本
 */
public record OperLogEntry(
        Long tenantId,
        Long userId,
        String module,
        String permCode,
        String method,
        String requestParams,
        Integer status,
        String errorMsg,
        String ip,
        String traceId,
        LocalDateTime operateTime
) {
}
