package com.minimall.sys.domain;

import com.minimall.infra.persistence.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 支付账号 → 租户映射(架构文档 6.1 的备选租户识别方案)。
 *
 * <p>平台级表:它不是租户表,"账号 → 租户"本来就该有租户维度,查它不算绕过隔离。
 * 只在"商户订单号格式被渠道定死、无法把 tenant_id 编码进去"时才用到;
 * 无论用哪种方式识别出租户,**解析结果都只是定位线索,订单归属一律以本地订单记录为准**(6.1)。
 */
@Entity
@Table(name = "sys_pay_account")
@Getter
@Setter
public class SysPayAccount extends BaseAuditEntity {

    /** 渠道标识,如 wx/alipay */
    @Column(name = "channel", nullable = false, length = 32)
    private String channel;

    /** 渠道侧账号标识,如商户号 mch_id */
    @Column(name = "pay_account_id", nullable = false, length = 64)
    private String payAccountId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "status", nullable = false)
    private Integer status;
}
