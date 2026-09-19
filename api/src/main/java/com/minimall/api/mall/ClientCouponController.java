package com.minimall.api.mall;

import com.minimall.api.mall.dto.ClientCouponView;
import com.minimall.common.ApiResponse;
import com.minimall.mall.service.CouponService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端 - 优惠券(商城设计文档 3.10)。
 *
 * <p>领券列表允许游客看(与商品浏览同理,便于用户先看到优惠再决定登录),
 * "我的券"与"领取"必须有客户端令牌 —— 券是绑定到客户身份的资产。
 */
@RestController
@RequestMapping("/mall/api/coupons")
public class ClientCouponController {

    private final CouponService couponService;

    public ClientCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /** 可领取的券(公开)。 */
    @GetMapping("/claimable")
    public ApiResponse<List<ClientCouponView>> claimable() {
        return ApiResponse.ok(couponService.claimable());
    }

    /** 我的券(需要令牌)。{@code status}:1-未使用 2-已使用 3-已过期,不传为全部。 */
    @GetMapping("/mine")
    public ApiResponse<List<ClientCouponView>> mine(@RequestParam(required = false) Integer status) {
        return ApiResponse.ok(couponService.mine(status));
    }

    /** 领取(需要令牌)。 */
    @PostMapping("/{couponId}/claim")
    public ApiResponse<Long> claim(@PathVariable Long couponId) {
        return ApiResponse.ok(couponService.claim(couponId));
    }
}
