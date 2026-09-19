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
 * 订单主表(商城设计文档 3.3、3.4)。
 *
 * <p><b>状态值必须与文档 3.4 的状态机一致,不要在各处硬编码数字</b>:
 * 这些常量定义在实体上,是为了让"1=待支付"这件事只有一个出处。
 *
 * <p><b>收货信息是快照</b>:{@code receiverName}/{@code receiverPhone}/{@code receiverAddress}
 * 在下单时写入,之后**不再跟随地址簿变化**(地址可能被买家删改)。
 *
 * <p>{@code orderNo} 对外展示(3.6:日期 + 当日序列),不把雪花 ID 暴露给前端 ——
 * 雪花 ID 会泄露下单时间与并发量级,而且长数字在小程序里不好复制。
 */
@Entity
@Table(name = "mall_order")
@Getter
@Setter
public class MallOrder extends BaseTenantEntity {

    /** 待支付。 */
    public static final int STATUS_PENDING_PAY = 1;
    /** 待发货(已支付)。 */
    public static final int STATUS_PENDING_SHIP = 2;
    /** 待收货(已发货)。 */
    public static final int STATUS_PENDING_RECEIVE = 3;
    /** 已完成(已确认收货)。 */
    public static final int STATUS_FINISHED = 4;
    /** 已取消(未支付关闭:超时/买家取消/商家取消)。 */
    public static final int STATUS_CANCELLED = 5;
    /** 售后中(存在进行中的售后单)。 */
    public static final int STATUS_AFTER_SALE = 6;

    /** 对外展示单号,格式见 3.6。 */
    @Column(name = "order_no", nullable = false, length = 32)
    private String orderNo;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "status", nullable = false)
    private Integer status;

    /** 商品总金额:下单时各 SKU 价格快照求和。 */
    @Column(name = "goods_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal goodsAmount;

    @Column(name = "freight_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal freightAmount;

    @Column(name = "coupon_discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal couponDiscountAmount;

    /** 满减活动减免金额。 */
    @Column(name = "promotion_discount_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal promotionDiscountAmount;

    /**
     * 实付金额 = 商品总额 - 满减 - 优惠券 + 运费(3.5)。
     *
     * <p>落库而不是每次算:价格与活动都可能变,只有落库的值才是"当时算出来的值",
     * 事后对账(尤其是退款金额)必须以它为准。
     */
    @Column(name = "pay_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal payAmount;

    /** 使用的优惠券领取记录,未使用为空。 */
    @Column(name = "coupon_record_id")
    private Long couponRecordId;

    @Column(name = "receiver_name", nullable = false, length = 32)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone;

    @Column(name = "receiver_address", nullable = false, length = 255)
    private String receiverAddress;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "logistics_company", length = 64)
    private String logisticsCompany;

    @Column(name = "logistics_no", length = 64)
    private String logisticsNo;

    @Column(name = "ship_time")
    private LocalDateTime shipTime;

    @Column(name = "receive_time")
    private LocalDateTime receiveTime;

    @Column(name = "pay_time")
    private LocalDateTime payTime;

    @Column(name = "cancel_time")
    private LocalDateTime cancelTime;

    /** 1-超时未支付 2-买家取消 3-商家取消。 */
    @Column(name = "close_reason")
    private Integer closeReason;

    @Column(name = "finish_time")
    private LocalDateTime finishTime;
}
