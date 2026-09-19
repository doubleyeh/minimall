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
 * 微信支付流水(商城设计文档 3.8)。
 *
 * <p><b>幂等的落点就是 {@code uk_wx_transaction} 这个唯一键</b>:微信回调可能重复推送,
 * 处理前先按 {@code wxTransactionId} 判断是否已有成功记录,重复的直接返回成功、不再走业务逻辑。
 * 只靠应用层"先查后插"挡不住并发推送,唯一键才是最终防线(与购物车同理)。
 *
 * <p>{@code rawCallback} 保留原始报文:微信支付的争议处理高度依赖原始数据,
 * 结构化解析后再存会丢掉排查所需的细节。
 */
@Entity
@Table(name = "mall_wx_payment")
@Getter
@Setter
public class MallWxPayment extends BaseTenantEntity {

    /** 待支付。 */
    public static final int PAY_STATUS_PENDING = 0;
    /** 支付成功。 */
    public static final int PAY_STATUS_SUCCESS = 1;
    /** 支付失败/已关闭。 */
    public static final int PAY_STATUS_FAILED = 2;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 微信支付订单号,回调后写入。 */
    @Column(name = "wx_transaction_id", length = 64)
    private String wxTransactionId;

    /** 商户订单号,等于 {@code mall_order.order_no}。 */
    @Column(name = "out_trade_no", nullable = false, length = 32)
    private String outTradeNo;

    @Column(name = "pay_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal payAmount;

    /** 0-待支付 1-支付成功 2-支付失败/已关闭。 */
    @Column(name = "pay_status", nullable = false)
    private Integer payStatus;

    /** 统一下单接口返回的预支付 ID。 */
    @Column(name = "prepay_id", length = 64)
    private String prepayId;

    @Column(name = "callback_time")
    private LocalDateTime callbackTime;

    /** 微信回调原始报文,便于对账排查。 */
    @Column(name = "raw_callback", columnDefinition = "text")
    private String rawCallback;
}
