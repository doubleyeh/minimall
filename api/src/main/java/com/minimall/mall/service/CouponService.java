package com.minimall.mall.service;

import com.minimall.api.mall.dto.ClientCouponView;
import com.minimall.api.mall.dto.CouponSaveRequest;
import com.minimall.api.mall.dto.CouponView;
import com.minimall.common.PageResult;

import java.util.List;

/**
 * 优惠券(商城设计文档 3.10)。
 *
 * <p>领取与使用是两个独立动作:领取只产生一条记录({@code status = 1}),
 * 下单使用时才关联订单并置为已使用 —— 使用逻辑在上单流程里({@code OrderServiceImpl}),
 * 本服务只负责"定义"与"领取"。
 */
public interface CouponService {

    // ---------------------------------------------------------- 管理端

    PageResult<CouponView> page(String couponName, Integer status, int pageNo, int pageSize);

    Long create(CouponSaveRequest request);

    void update(Long couponId, CouponSaveRequest request);

    /** 启停。停用后不能再被领取,但已领取的券仍可在有效期内使用(不追溯)。 */
    void changeStatus(Long couponId, Integer status);

    // ---------------------------------------------------------- 小程序端

    /** 当前可领取的券(进行中且在有效期内)。 */
    List<ClientCouponView> claimable();

    /** 我的券。{@code status} 为空返回全部(未使用/已使用/已过期)。 */
    List<ClientCouponView> mine(Integer status);

    /**
     * 领取。名额靠条件更新(受影响行数 0 即已领完),每人限领先校验。
     *
     * @return 领取记录 ID
     */
    Long claim(Long couponId);
}
