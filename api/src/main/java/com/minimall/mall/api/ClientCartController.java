package com.minimall.mall.api;

import com.minimall.mall.api.dto.CartAddRequest;
import com.minimall.mall.api.dto.CartItemView;
import com.minimall.mall.api.dto.CartUpdateRequest;
import com.minimall.common.ApiResponse;
import com.minimall.mall.service.CartService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端 - 购物车(商城设计文档 2)。
 *
 * <p>这些接口**必须携带客户端令牌**(不在公开清单里),由 {@code ClientAuthFilter} 统一拦截:
 * 购物车条目只能属于令牌里的那个客户,接口本身不接受 customerId 参数。
 */
@RestController
@RequestMapping("/mall/api/cart")
public class ClientCartController {

    private final CartService cartService;

    public ClientCartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping
    public ApiResponse<List<CartItemView>> list() {
        return ApiResponse.ok(cartService.list());
    }

    @PostMapping
    public ApiResponse<Long> add(@Valid @RequestBody CartAddRequest request) {
        return ApiResponse.ok(cartService.add(request));
    }

    @PutMapping("/{cartId}")
    public ApiResponse<Void> update(@PathVariable Long cartId, @Valid @RequestBody CartUpdateRequest request) {
        cartService.update(cartId, request);
        return ApiResponse.ok();
    }

    @DeleteMapping
    public ApiResponse<Void> remove(@RequestBody List<Long> cartIds) {
        cartService.remove(cartIds);
        return ApiResponse.ok();
    }

    @DeleteMapping("/all")
    public ApiResponse<Void> clear() {
        cartService.clear();
        return ApiResponse.ok();
    }
}
