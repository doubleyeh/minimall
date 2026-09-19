package com.minimall.api.mall;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.api.mall.dto.CouponSaveRequest;
import com.minimall.api.mall.dto.CouponView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditLog;
import com.minimall.mall.service.CouponService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家管理端 - 优惠券(商城设计文档 3.10)。
 *
 * <p>没有"删除"接口:优惠券一旦发出去就有领取记录,删除会让这些记录失去含义。
 * 下架用 {@code PUT /{couponId}/status} 停用即可(已领取的券仍可在有效期内使用)。
 */
@RestController
@RequestMapping("/mall/admin/coupons")
public class MallCouponController {

    private final CouponService couponService;

    public MallCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @GetMapping
    @SaCheckPermission("mall:coupon:list")
    public ApiResponse<PageResult<CouponView>> page(@RequestParam(required = false) String couponName,
                                                    @RequestParam(required = false) Integer status,
                                                    @RequestParam(defaultValue = "1") int pageNo,
                                                    @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(couponService.page(couponName, status, pageNo, pageSize));
    }

    @AuditLog(module = "优惠券", permCode = "mall:coupon:create")
    @PostMapping
    @SaCheckPermission("mall:coupon:create")
    public ApiResponse<Long> create(@Valid @RequestBody CouponSaveRequest request) {
        return ApiResponse.ok(couponService.create(request));
    }

    @AuditLog(module = "优惠券", permCode = "mall:coupon:update")
    @PutMapping("/{couponId}")
    @SaCheckPermission("mall:coupon:update")
    public ApiResponse<Void> update(@PathVariable Long couponId, @Valid @RequestBody CouponSaveRequest request) {
        couponService.update(couponId, request);
        return ApiResponse.ok();
    }

    @AuditLog(module = "优惠券", permCode = "mall:coupon:update")
    @PutMapping("/{couponId}/status")
    @SaCheckPermission("mall:coupon:update")
    public ApiResponse<Void> changeStatus(@PathVariable Long couponId, @RequestParam Integer status) {
        couponService.changeStatus(couponId, status);
        return ApiResponse.ok();
    }
}
