package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 售后单主表(商城设计文档 3.9)。
 *
 * <p>状态机有 10 个态,其中 4/8/9/10 是终态。**判断"是否还能操作"一律用状态值,不要用时间**:
 * 超时任务会按状态流转,时间只用于触发流转,不作为"是否已过期"的判据(两处判断标准不一致时,
 * 会出现"任务还没跑到但页面已经不让操作了"这类争吵不休的问题)。
 *
 * <p>{@code refundAmount} 只能由商家**下调**、不能上调,且不得超过订单明细行的
 * {@code totalAmount};下调操作必须记入 {@link MallAfterSaleLog} 的 remark ——
 * 少退给买家的金额属于必须留痕的操作。
 */
@Entity
@Table(name = "mall_after_sale")
@Getter
@Setter
public class MallAfterSale extends BaseTenantEntity {

    /** 仅退款。 */
    public static final int TYPE_REFUND_ONLY = 1;
    /** 退货退款。 */
    public static final int TYPE_RETURN_REFUND = 2;
    /** 换货。 */
    public static final int TYPE_EXCHANGE = 3;

    /** 待商家处理。 */
    public static final int STATUS_PENDING = 1;
    /** 商家已同意,待买家退货(仅退货退款/换货)。 */
    public static final int STATUS_WAIT_RETURN = 2;
    /** 买家已退货,待商家确认收货。 */
    public static final int STATUS_WAIT_RECEIVE = 3;
    /** 退款成功/换货完成(终态)。 */
    public static final int STATUS_DONE = 4;
    /** 商家拒绝申请。 */
    public static final int STATUS_REJECTED = 5;
    /** 商家拒绝收货。 */
    public static final int STATUS_REJECT_RECEIVE = 6;
    /** 平台客服介入中。 */
    public static final int STATUS_ARBITRATING = 7;
    /** 客服仲裁通过(终态)。 */
    public static final int STATUS_ARBITRATION_PASS = 8;
    /** 客服仲裁驳回(终态)。 */
    public static final int STATUS_ARBITRATION_REJECT = 9;
    /** 已关闭(撤销/超时,终态)。 */
    public static final int STATUS_CLOSED = 10;

    @Column(name = "after_sale_no", nullable = false, length = 32)
    private String afterSaleNo;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** 1-仅退款 2-退货退款 3-换货。 */
    @Column(name = "after_sale_type", nullable = false)
    private Integer afterSaleType;

    @Column(name = "status", nullable = false)
    private Integer status;

    @Column(name = "apply_reason", nullable = false, length = 64)
    private String applyReason;

    @Column(name = "apply_desc", length = 500)
    private String applyDesc;

    /** 申请退款金额:商家只能下调,不得超过订单明细行 totalAmount。 */
    @Column(name = "refund_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal refundAmount;

    /** 商家拒绝申请或拒绝收货都用它,靠 status 区分是哪一个环节。 */
    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    @Column(name = "return_logistics_company", length = 64)
    private String returnLogisticsCompany;

    @Column(name = "return_logistics_no", length = 64)
    private String returnLogisticsNo;

    @Column(name = "return_time")
    private LocalDateTime returnTime;

    @Column(name = "receive_confirm_time")
    private LocalDateTime receiveConfirmTime;

    /** 换货场景商家重新发货的物流。 */
    @Column(name = "reship_logistics_company", length = 64)
    private String reshipLogisticsCompany;

    @Column(name = "reship_logistics_no", length = 64)
    private String reshipLogisticsNo;

    @Column(name = "arbitration_time")
    private LocalDateTime arbitrationTime;

    @Column(name = "arbitration_remark", length = 500)
    private String arbitrationRemark;

    @Column(name = "finish_time")
    private LocalDateTime finishTime;

    /** 是否终态(4/8/9/10)。订单状态回退与"能否重复申请"都以此判断。 */
    public boolean isFinalStatus() {
        return status != null && (status == STATUS_DONE || status == STATUS_ARBITRATION_PASS
                || status == STATUS_ARBITRATION_REJECT || status == STATUS_CLOSED);
    }
}
