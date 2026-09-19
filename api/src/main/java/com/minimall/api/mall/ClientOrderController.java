package com.minimall.api.mall;

import com.minimall.api.mall.dto.ClientOrderView;
import com.minimall.api.mall.dto.CreateOrderRequest;
import com.minimall.api.mall.dto.OrderCreateResponse;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.mall.service.OrderService;
import com.minimall.mall.service.PayService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序端 - 订单(商城设计文档 3.3、3.4)。
 *
 * <p>全部需要客户端令牌:订单只能属于令牌里的那个客户,接口不接受 customerId 参数。
 */
@RestController
@RequestMapping("/mall/api/orders")
public class ClientOrderController {

    private final OrderService orderService;
    private final PayService payService;

    public ClientOrderController(OrderService orderService, PayService payService) {
        this.orderService = orderService;
        this.payService = payService;
    }

    @PostMapping
    public ApiResponse<OrderCreateResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(orderService.create(request));
    }

    @GetMapping
    public ApiResponse<PageResult<ClientOrderView>> list(@RequestParam(required = false) Integer status,
                                                         @RequestParam(defaultValue = "1") int pageNo,
                                                         @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(orderService.list(status, pageNo, pageSize));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<ClientOrderView> detail(@PathVariable Long orderId) {
        return ApiResponse.ok(orderService.detail(orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<Void> cancel(@PathVariable Long orderId) {
        orderService.cancel(orderId);
        return ApiResponse.ok();
    }

    @PostMapping("/{orderId}/receive")
    public ApiResponse<Void> receive(@PathVariable Long orderId) {
        orderService.confirmReceive(orderId);
        return ApiResponse.ok();
    }

    /**
     * 拉起支付。返回小程序 {@code wx.requestPayment} 所需参数(3.3 第 7 步的网络调用在这里,不在下单事务里)。
     */
    @PostMapping("/{orderId}/prepay")
    public ApiResponse<OrderCreateResponse.PayParams> prepay(@PathVariable Long orderId) {
        return ApiResponse.ok(payService.prepay(orderId));
    }
}
