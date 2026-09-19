package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 客户收货地址(商城设计文档 2)。
 *
 * <p>地址可被买家随时删改,所以**下单时必须把收货信息快照进 {@code mall_order}**(见 3.3 第 4 步)——
 * 订单详情页永远不能去实时读这张表,否则买家改一次地址,历史订单的收货信息就跟着变了。
 */
@Entity
@Table(name = "mall_customer_address")
@Getter
@Setter
public class MallCustomerAddress extends BaseTenantEntity {

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "receiver_name", nullable = false, length = 32)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone;

    @Column(name = "province", nullable = false, length = 32)
    private String province;

    @Column(name = "city", nullable = false, length = 32)
    private String city;

    @Column(name = "district", nullable = false, length = 32)
    private String district;

    @Column(name = "detail_address", nullable = false, length = 255)
    private String detailAddress;

    /**
     * 1-默认地址。
     *
     * <p>"同一客户至多一条为 1"由应用层保证 —— 设默认地址时必须先把同一客户的其他地址置 0,
     * 两步在同一事务内完成。数据库层没有部分唯一索引(MySQL 不支持),
     * 所以这个不变量只能靠"所有写入口都走同一个方法"来保证。
     */
    @Column(name = "is_default", nullable = false)
    private Integer isDefault;
}
