package com.minimall.mall.service;

import com.minimall.api.mall.dto.CartAddRequest;
import com.minimall.api.mall.dto.CartItemView;
import com.minimall.api.mall.dto.CartUpdateRequest;

import java.util.List;

/**
 * 购物车(商城设计文档 2)。
 *
 * <p>所有操作都只作用于**当前登录客户**自己的数据:{@code customerId} 取自
 * {@link com.minimall.mall.infra.auth.ClientContext},不接受客户端传入 ——
 * 只要有一个入口允许传 customerId,就会有人用它去读别人的购物车。
 */
public interface CartService {

    List<CartItemView> list();

    /** 加购:同一 SKU 已存在时累加数量。 */
    Long add(CartAddRequest request);

    void update(Long cartId, CartUpdateRequest request);

    void remove(List<Long> cartIds);

    /** 清空购物车。 */
    void clear();
}
