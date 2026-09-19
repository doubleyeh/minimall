package com.minimall.mall.api;

import com.minimall.mall.api.dto.ReviewCreateRequest;
import com.minimall.mall.api.dto.ReviewView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.mall.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序端 - 商品评价(商城设计文档 2)。
 *
 * <p>查看评价是公开的(游客也要能看,否则商品页会显得"没人买过");
 * 提交评价需要令牌。
 */
@RestController
@RequestMapping("/mall/api/reviews")
public class ClientReviewController {

    private final ReviewService reviewService;

    public ClientReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /** 某商品的评价列表(公开)。 */
    @GetMapping("/goods/{goodsId}")
    public ApiResponse<PageResult<ReviewView>> listByGoods(@PathVariable Long goodsId,
                                                           @RequestParam(defaultValue = "1") int pageNo,
                                                           @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(reviewService.listByGoods(goodsId, pageNo, pageSize));
    }

    /** 提交评价(需要令牌)。 */
    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody ReviewCreateRequest request) {
        return ApiResponse.ok(reviewService.create(request));
    }
}
