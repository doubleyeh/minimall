package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 商城客户(商城设计文档 3.1):**独立于 {@code sys_user} 的账号体系**。
 *
 * <p>为什么不复用 {@code sys_user}:两端会话生命周期差一个数量级 ——
 * 后台管理是分钟/小时级、需要强制改密与租户切换;小程序是"免登录天/周级"、靠微信静默重登。
 * 硬塞进一张表会让每处逻辑都要先判断"这是哪类用户",而且客户的宽松登录策略
 * 会悄悄削弱后台的严格策略。
 *
 * <p>本表同样继承 {@link BaseTenantEntity}:微信 openid 是按小程序(即租户)发放的,
 * 同一个微信号在不同租户的小程序里是**两个不同的 openid**,所以唯一键只能是
 * {@code (tenant_id, openid)},不能是 openid 本身。
 */
@Entity
@Table(name = "mall_customer")
@Getter
@Setter
public class MallCustomer extends BaseTenantEntity {

    @Column(name = "openid", nullable = false, length = 64)
    private String openid;

    /** 微信开放平台 unionid,同一微信主体下多端(公众号/APP)打通时使用。 */
    @Column(name = "unionid", length = 64)
    private String unionid;

    @Column(name = "nickname", length = 64)
    private String nickname;

    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    @Column(name = "phone", length = 20)
    private String phone;

    /** 0-未知 1-男 2-女。 */
    @Column(name = "gender")
    private Integer gender;

    /** 0-禁用 1-正常。禁用后客户端 token 立即失效(校验时判状态)。 */
    @Column(name = "status", nullable = false)
    private Integer status;

    /** 当前会员等级,为空表示默认等级(应用层兜底展示为"普通会员")。 */
    @Column(name = "member_level_id")
    private Long memberLevelId;

    /**
     * 当前可用积分。
     *
     * <p><b>不允许绕过流水直接改它</b>:任何变动都要同时写一条 {@link MallPointsLog},
     * 并带上变动后余额。积分是可兑换的资产,没有流水就无法对账。
     */
    @Column(name = "points", nullable = false)
    private Integer points;

    /** 成长值:用于等级晋升判断,**累计不清零**(区别于可消耗的积分)。 */
    @Column(name = "growth_value", nullable = false)
    private Integer growthValue;

    @Column(name = "register_time", nullable = false)
    private LocalDateTime registerTime;

    @Column(name = "last_login_time")
    private LocalDateTime lastLoginTime;
}
