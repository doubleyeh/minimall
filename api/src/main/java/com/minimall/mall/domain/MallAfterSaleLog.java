package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 售后状态流转记录(商城设计文档 2)。
 *
 * <p>与 {@link MallOrderStatusLog} 是两条独立审计链:订单的状态变化与售后的状态变化是
 * 两个不同的过程,合并成一张表后,"这单被退款了几次"这类问题就要靠字段区分来查。
 */
@Entity
@Table(name = "mall_after_sale_log")
@Getter
@Setter
public class MallAfterSaleLog extends BaseTenantEntity {

    /** 买家。 */
    public static final int OPERATOR_BUYER = 1;
    /** 商家。 */
    public static final int OPERATOR_MERCHANT = 2;
    /** 系统自动。 */
    public static final int OPERATOR_SYSTEM = 3;
    /**
     * 平台客服。
     *
     * <p>本期没有独立客服角色,由商家(或超管)在管理端处理,但操作人仍记这个值 ——
     * 字段含义为将来接入独立客服角色保持稳定,不必回头看历史数据。
     */
    public static final int OPERATOR_PLATFORM = 4;

    @Column(name = "after_sale_id", nullable = false)
    private Long afterSaleId;

    @Column(name = "from_status")
    private Integer fromStatus;

    @Column(name = "to_status", nullable = false)
    private Integer toStatus;

    /** 1-买家 2-商家 3-系统自动 4-平台客服。 */
    @Column(name = "operator_type", nullable = false)
    private Integer operatorType;

    @Column(name = "operator_id")
    private Long operatorId;

    /** 商家下调退款金额、拒绝理由等都要写在这里(3.9)。 */
    @Column(name = "remark", length = 255)
    private String remark;
}
