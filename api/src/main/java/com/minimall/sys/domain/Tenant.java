package com.minimall.sys.domain;

import com.minimall.infra.persistence.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 租户(架构文档 4.6.1:平台级表,不受租户过滤,天然只能被平台超管查询)。
 *
 * <p>注意这里**没有** {@code isSuper} 字段:"跳过租户过滤"是用户级标志,在 {@code sys_user.is_super}
 * 上(见 4.1/4.10)。挂在租户上会让该租户下所有用户(包括普通员工)一起跨租户。
 */
@Entity
@Table(name = "tenant")
@Getter
@Setter
public class Tenant extends BaseAuditEntity {

    @Column(name = "tenant_code", nullable = false, length = 32)
    private String tenantCode;

    @Column(name = "tenant_name", nullable = false, length = 64)
    private String tenantName;

    /** 0-禁用 1-正常。禁用后在线会话按 4.11 在下一个请求失效 */
    @Column(name = "status", nullable = false)
    private Integer status;

    /** 套餐 ID。为空表示"不限"(兼容历史数据);建租户接口要求必填,见 4.8 */
    @Column(name = "package_id")
    private Long packageId;

    /** 为空表示不过期 */
    @Column(name = "expire_time")
    private LocalDateTime expireTime;
}
