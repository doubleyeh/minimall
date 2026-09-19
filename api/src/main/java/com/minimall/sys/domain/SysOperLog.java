package com.minimall.sys.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 操作日志(架构文档 7.2)。
 *
 * <p>三点特殊之处:
 * <ol>
 *   <li>{@code tenantId}/{@code userId} **允许为空**:登录失败这类场景还没识别出租户与用户,
 *       而这条日志恰恰是排查撞库/锁定时最有价值的,不能因为非空约束把它丢掉。
 *       所以本表覆盖了 {@link #tenantRequired()} 返回 false(4.5 里唯一的例外)</li>
 *   <li>异步落库时**不能依赖自动回填**:异步线程里 TenantContext 与 Sa-Token 会话都是空的,
 *       必须显式赋值 tenant_id/user_id/create_by,见 4.12</li>
 *   <li>写入前必须脱敏与截断(7.2):登录接口的请求体包含明文密码,绝不能原样落库</li>
 * </ol>
 */
@Entity
@Table(name = "sys_oper_log")
@Getter
@Setter
public class SysOperLog extends BaseTenantEntity {

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "module", nullable = false, length = 64)
    private String module;

    @Column(name = "perm_code", length = 128)
    private String permCode;

    @Column(name = "method", nullable = false, length = 255)
    private String method;

    /** 已脱敏、已截断的请求参数 */
    @Column(name = "request_params", columnDefinition = "text")
    private String requestParams;

    /** 0-失败 1-成功 */
    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "error_msg", columnDefinition = "text")
    private String errorMsg;

    @Column(name = "ip", length = 64)
    private String ip;

    /** 与 MDC/响应头 X-Trace-Id 对应(7.2) */
    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Override
    protected boolean tenantRequired() {
        return false;
    }
}
