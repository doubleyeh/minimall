package com.minimall.sys.domain.repository;

import com.minimall.sys.domain.SysOperLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 操作日志仓储(架构文档 7.2、4.6.1)。
 *
 * <p>只写不更不删,不需要额外查询方法:按租户查日志直接走标准的租户过滤
 * ({@code sys_oper_log} 是 {@code BaseTenantEntity},超管查全平台时靠 {@code isSuperUser} 豁免)。
 *
 * <p><b>写入来自异步线程</b>(7.2 要求异步落库、不阻塞主流程)。异步线程里
 * {@code TenantContext}/{@code AuditContext} 都是空的,所以 {@code tenant_id}/{@code user_id}
 * 必须由写入方**显式赋值**,不能指望 4.5 的自动回填——这也是这两列在库上允许为空的原因(覆盖登录失败这类场景)。
 */
public interface SysOperLogRepository extends JpaRepository<SysOperLog, Long> {

    /**
     * 按 traceId 取最近一条。
     *
     * <p>用途:异步落库意味着"接口已经返回、日志可能还没写进去",排查时只能靠 traceId 回捞;
     * 测试里断言"异步线程写入的 tenant_id/user_id 与提交时一致"(8.4)也依赖它。
     */
    Optional<SysOperLog> findFirstByTraceIdOrderByIdDesc(String traceId);
}
