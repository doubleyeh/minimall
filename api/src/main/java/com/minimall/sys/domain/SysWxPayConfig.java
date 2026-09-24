package com.minimall.sys.domain;

import com.minimall.infra.persistence.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 微信支付渠道配置(平台管理,每租户一行)。
 *
 * <p>密钥类字段存的是信封加密后的密文,读写都要过 {@code SecretCipher}。
 * {@code tenantId} 是普通列而不是继承 {@code BaseTenantEntity}:平台端要跨租户管理,
 * 回调侧也是先由路径里的 tenantCode 定租户再读配置,不需要租户过滤器兜底。
 */
@Entity
@Table(name = "sys_wx_pay_config")
@Getter
@Setter
public class SysWxPayConfig extends BaseAuditEntity {

    /** 普通商户:自己就是收款商户 */
    public static final String MODE_DIRECT = "direct";
    /** 服务商模式:服务商收款,钱进特约商户的账户 */
    public static final String MODE_PARTNER = "partner";

    /** 登录 code2Session 用 app_id 那套凭据 */
    public static final String LOGIN_SOURCE_APP = "app";
    /** 登录 code2Session 用 sub_app_id 那套凭据 */
    public static final String LOGIN_SOURCE_SUB = "sub";

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "pay_mode", nullable = false, length = 16)
    private String payMode;

    /** direct:商户号;partner:服务商商户号 sp_mchid */
    @Column(name = "mch_id", nullable = false, length = 32)
    private String mchId;

    /** partner:特约商户号 sub_mchid */
    @Column(name = "sub_mch_id", length = 32)
    private String subMchId;

    /** direct:商户小程序 appid;partner:服务商小程序 sp_appid */
    @Column(name = "app_id", nullable = false, length = 32)
    private String appId;

    @Column(name = "app_secret_enc", nullable = false, length = 512)
    private String appSecretEnc;

    /** partner:特约商户小程序 sub_appid */
    @Column(name = "sub_app_id", length = 32)
    private String subAppId;

    @Column(name = "sub_app_secret_enc", length = 512)
    private String subAppSecretEnc;

    /** 登录 code2Session 用哪套凭据:app / sub */
    @Column(name = "login_app_source", nullable = false, length = 8)
    private String loginAppSource;

    @Column(name = "api_v3_key_enc", nullable = false, length = 512)
    private String apiV3KeyEnc;

    @Column(name = "merchant_serial_no", nullable = false, length = 64)
    private String merchantSerialNo;

    @Column(name = "merchant_private_key_enc", nullable = false, columnDefinition = "TEXT")
    private String merchantPrivateKeyEnc;

    @Column(name = "platform_serial_no", length = 64)
    private String platformSerialNo;

    @Column(name = "platform_public_key_enc", columnDefinition = "TEXT")
    private String platformPublicKeyEnc;

    /** 0-停用 1-正常 */
    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "remark", length = 255)
    private String remark;

    public boolean isPartner() {
        return MODE_PARTNER.equals(payMode);
    }

    public boolean isEnabled() {
        return status != null && status == 1;
    }
}
