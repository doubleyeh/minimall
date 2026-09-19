package com.minimall.mall.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.mall.api.dto.ReviewView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditLog;
import com.minimall.mall.service.ReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家管理端 - 商品评价(商城设计文档 2)。
 */
@RestController
@RequestMapping("/mall/admin/reviews")
public class MallReviewController {

    private final ReviewService reviewService;

    public MallReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    @SaCheckPermission("mall:review:list")
    public ApiResponse<PageResult<ReviewView>> page(@RequestParam(required = false) Long goodsId,
                                                    @RequestParam(required = false) Integer status,
                                                    @RequestParam(defaultValue = "1") int pageNo,
                                                    @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(reviewService.page(goodsId, status, pageNo, pageSize));
    }

    @AuditLog(module = "商品评价", permCode = "mall:review:reply")
    @PutMapping("/{reviewId}/reply")
    @SaCheckPermission("mall:review:reply")
    public ApiResponse<Void> reply(@PathVariable Long reviewId, @RequestBody ReplyRequest request) {
        reviewService.reply(reviewId, request.content());
        return ApiResponse.ok();
    }

    @AuditLog(module = "商品评价", permCode = "mall:review:status")
    @PutMapping("/{reviewId}/status")
    @SaCheckPermission("mall:review:status")
    public ApiResponse<Void> changeStatus(@PathVariable Long reviewId, @RequestParam Integer status) {
        reviewService.changeStatus(reviewId, status);
        return ApiResponse.ok();
    }

    /** 回复请求。 */
    public record ReplyRequest(String content) {
    }
}
