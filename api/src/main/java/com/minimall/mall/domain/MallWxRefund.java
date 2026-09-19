package com.minimall.mall.domain;

import com.minimall.domain.sys.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 微信退款流水(商城设计文档 3.9)。
 *
 * <p>与支付流水分表而不是加个类型字段合并:两者的字段与状态机差异很大
 * (退款多了 {@code afterSaleId}、退款有自己的回调与失败重试),合表会让一半字段永远为空。
 *
 * <p>幂等方式与支付一致:{@code uk_wx_refund} 唯一键兜底,回调重复推送不会重复退款。
 */
@Entity
@Table(name = "mall_wx_refund")
@Getter
@Setter
public class MallWxRefund extends BaseTenantEntity {

    /** 申请中。 */
    public static final int REFUND_STATUS_APPLYING = 0;
    /** 退款成功。 */
    public static final int REFUND_STATUS_SUCCESS = 1;
    /** 退款失败。 */
    public static final int REFUND_STATUS_FAILED = 2;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "after_sale_id", nullable = false)
    private Long afterSaleId;

    /** 微信退款单号,回调后写入。 */
    @Column(name = "wx_refund_id", length = 64)
    private String wxRefundId;

    /** 商户退款单号。 */
    @Column(name = "out_refund_no", nullable = false, length = 32)
    private String outRefundNo;

    @Column(name = "refund_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal refundAmount;

    /** 0-申请中 1-退款成功 2-退款失败。 */
    @Column(name = "refund_status", nullable = false)
    private Integer refundStatus;

    @Column(name = "callback_time")
    private LocalDateTime callbackTime;

    /** 微信回调原始报文。 */
    @Column(name = "raw_callback", columnDefinition = "text")
    private String rawCallback;
}
