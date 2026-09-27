package com.minimall.sys.domain.repository;

import com.minimall.sys.domain.SysOperLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 操作日志仓储(架构文档 7.2、4.6.1)。
 *
 * <p>只写不更不删,查询用 Querydsl 的动态条件({@link QuerydslPredicateExecutor}):
 * 日志的筛选维度多(租户/用户/模块/状态/时间范围)且都可选,拼 SQL 字符串容易出错。
 *
 * <p>按租户查日志走标准的租户过滤({@code sys_oper_log} 是 {@code BaseTenantEntity},
 * 超管查全平台时靠 {@code isSuperUser} 豁免)。
 *
 * <p><b>写入来自异步线程</b>(7.2 要求异步落库、不阻塞主流程)。异步线程里
 * {@code TenantContext}/{@code AuditContext} 都是空的,所以 {@code tenant_id}/{@code user_id}
 * 必须由写入方**显式赋值**,不能指望 4.5 的自动回填——这也是这两列在库上允许为空的原因(覆盖登录失败这类场景)。
 */
public interface SysOperLogRepository extends JpaRepository<SysOperLog, Long>,
        QuerydslPredicateExecutor<SysOperLog> {

    /**
     * 按 traceId 取最近一条。
     *
     * <p>用途:异步落库意味着"接口已经返回、日志可能还没写进去",排查时只能靠 traceId 回捞;
     * 测试里断言"异步线程写入的 tenant_id/user_id 与提交时一致"(8.4)也依赖它。
     */
    Optional<SysOperLog> findFirstByTraceIdOrderByIdDesc(String traceId);

    /**
     * 归档后按游标删除:只删 {@code deadline} 之前、且 ID 不超过 {@code maxId} 的日志。
     *
     * <p>用 ID 做游标(而不是只按时间)是为了让"边写文件边删库"有一个稳定的推进边界:
     * 时间相同的行可能有很多,只按时间删会把还没写进归档文件的行一起删掉。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from SysOperLog l where l.createTime < :deadline and l.id <= :maxId")
    int deleteArchivedUpTo(@Param("deadline") LocalDateTime deadline, @Param("maxId") Long maxId);
}
