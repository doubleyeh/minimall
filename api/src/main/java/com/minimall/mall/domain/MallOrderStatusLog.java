package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 订单状态流转记录(商城设计文档 2)。
 *
 * <p>订单状态的每一次变化都追加一条。它回答的是"这单为什么变成现在这样":
 * 谁在什么时候把它从待支付改成了已取消。系统自动流转也要记(operatorType=3),
 * 否则超时关闭这种最常见的情况反而查不到原因。
 */
@Entity
@Table(name = "mall_order_status_log")
@Getter
@Setter
public class MallOrderStatusLog extends BaseTenantEntity {

    /** 买家。 */
    public static final int OPERATOR_BUYER = 1;
    /** 商家。 */
    public static final int OPERATOR_MERCHANT = 2;
    /** 系统自动。 */
    public static final int OPERATOR_SYSTEM = 3;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "from_status")
    private Integer fromStatus;

    @Column(name = "to_status", nullable = false)
    private Integer toStatus;

    /** 1-买家 2-商家 3-系统自动。 */
    @Column(name = "operator_type", nullable = false)
    private Integer operatorType;

    /** 操作人 ID:买家为 customerId、商家为 sys_user.id、系统自动为空。 */
    @Column(name = "operator_id")
    private Long operatorId;

    @Column(name = "remark", length = 255)
    private String remark;
}
