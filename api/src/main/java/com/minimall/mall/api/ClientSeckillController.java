package com.minimall.mall.api;

import com.minimall.common.ApiResponse;
import com.minimall.common.ErrorCode;
import com.minimall.infra.security.RedisRateLimiter;
import com.minimall.mall.api.dto.ClientSeckillHomeView;
import com.minimall.mall.api.dto.ClientSeckillItemView;
import com.minimall.mall.api.dto.CreateSeckillOrderRequest;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.api.dto.SeckillPreviewRequest;
import com.minimall.mall.api.dto.SeckillPreviewView;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.service.OrderService;
import com.minimall.mall.service.SeckillClientService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 小程序端 - 秒杀(商城设计文档 3.12)。
 *
 * <p>下单接口单独做限流:秒杀是唯一会被脚本刷的入口,10 次/10 秒足以挡住连点与简单脚本,
 * 又不会误伤正常用户。真正的并发保护在 Redis 闸门与数据库的条件更新里。
 */
@RestController
@RequestMapping("/mall/api/seckills")
public class ClientSeckillController {

    /** 同一客户在窗口内最多提交几次秒杀下单(含失败)。 */
    private static final int ORDER_LIMIT = 10;
    private static final Duration ORDER_WINDOW = Duration.ofSeconds(10);

    private final SeckillClientService seckillClientService;
    private final OrderService orderService;
    private final RedisRateLimiter rateLimiter;

    public ClientSeckillController(SeckillClientService seckillClientService,
                                   OrderService orderService,
                                   RedisRateLimiter rateLimiter) {
        this.seckillClientService = seckillClientService;
        this.orderService = orderService;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public ApiResponse<ClientSeckillHomeView> home() {
        return ApiResponse.ok(seckillClientService.home());
    }

    @GetMapping("/{itemId}")
    public ApiResponse<ClientSeckillItemView> detail(@PathVariable Long itemId) {
        return ApiResponse.ok(seckillClientService.detail(itemId));
    }

    /** 结算试算:金额全部由服务端算,端上不出现任何金额运算。 */
    @PostMapping("/{itemId}/preview")
    public ApiResponse<SeckillPreviewView> preview(@PathVariable Long itemId,
                                                   @Valid @RequestBody SeckillPreviewRequest request) {
        return ApiResponse.ok(orderService.previewSeckill(itemId, request.addressId(), request.quantity()));
    }

    @PostMapping("/{itemId}/orders")
    public ApiResponse<OrderCreateResponse> create(@PathVariable Long itemId,
                                                   @Valid @RequestBody CreateSeckillOrderRequest request) {
        Long customerId = ClientContext.requireCustomerId();
        rateLimiter.checkAndCount("seckill:req:" + customerId, ORDER_LIMIT, ORDER_WINDOW,
                ErrorCode.IP_RATE_LIMITED);
        return ApiResponse.ok(orderService.createSeckill(itemId, request.quantity(), request.addressId()));
    }
}
