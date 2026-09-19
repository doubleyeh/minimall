package com.minimall.mall.service.impl;

import com.minimall.api.mall.dto.CartAddRequest;
import com.minimall.api.mall.dto.CartItemView;
import com.minimall.api.mall.dto.CartUpdateRequest;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.domain.MallCart;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallCartRepository;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.service.CartService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 购物车实现(商城设计文档 2)。
 *
 * <p>加购这条路径上有一个必须处理的并发细节:用户连续点两次"加入购物车"时,
 * 两个请求可能都查不到已有条目,于是都尝试插入,第二个会被唯一键
 * {@code uk_tenant_customer_sku} 拒绝。**这里必须捕获冲突并改成累加** ——
 * 直接让异常冒出去,用户看到的是"加购失败",而实际上购物车里已经有了一件。
 */
@Service
@Transactional
public class CartServiceImpl implements CartService {

    private static final int STATUS_ENABLED = 1;
    private static final int MAX_QUANTITY = 999;

    private final MallCartRepository cartRepository;
    private final MallSkuRepository skuRepository;
    private final MallGoodsRepository goodsRepository;

    public CartServiceImpl(MallCartRepository cartRepository,
                           MallSkuRepository skuRepository,
                           MallGoodsRepository goodsRepository) {
        this.cartRepository = cartRepository;
        this.skuRepository = skuRepository;
        this.goodsRepository = goodsRepository;
    }

    @Override
    public List<CartItemView> list() {
        Long customerId = ClientContext.requireCustomerId();
        List<MallCart> carts = cartRepository.findByCustomerIdOrderByIdDesc(customerId);
        if (carts.isEmpty()) {
            return List.of();
        }
        Map<Long, MallSku> skuById = new HashMap<>();
        skuRepository.findAllById(carts.stream().map(MallCart::getSkuId).distinct().toList())
                .forEach(sku -> skuById.put(sku.getId(), sku));
        Map<Long, MallGoods> goodsById = new HashMap<>();
        List<Long> goodsIds = skuById.values().stream().map(MallSku::getGoodsId).distinct().toList();
        if (!goodsIds.isEmpty()) {
            goodsRepository.findAllById(goodsIds).forEach(goods -> goodsById.put(goods.getId(), goods));
        }

        return carts.stream().map(cart -> {
            MallSku sku = skuById.get(cart.getSkuId());
            MallGoods goods = sku == null ? null : goodsById.get(sku.getGoodsId());
            boolean valid = sku != null && sku.getStatus() != null && sku.getStatus() == STATUS_ENABLED
                    && goods != null && goods.getStatus() != null && goods.getStatus() == STATUS_ENABLED
                    && sku.availableStock() > 0;
            return new CartItemView(cart.getId(), cart.getSkuId(),
                    sku == null ? null : sku.getGoodsId(),
                    goods == null ? null : goods.getGoodsName(),
                    sku == null ? null : sku.getSkuName(),
                    sku == null ? null : (sku.getSkuImage() != null ? sku.getSkuImage()
                            : (goods == null ? null : goods.getMainImage())),
                    sku == null ? null : sku.getPrice(),
                    sku == null ? 0 : sku.availableStock(),
                    cart.getQuantity(), cart.getSelected(), valid);
        }).toList();
    }

    @Override
    public Long add(CartAddRequest request) {
        Long customerId = ClientContext.requireCustomerId();
        MallSku sku = skuRepository.findById(request.skuId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品规格不存在"));
        requireSellable(sku);

        int quantity = request.quantity();
        MallCart existing = cartRepository.findByCustomerIdAndSkuId(customerId, request.skuId()).orElse(null);
        if (existing != null) {
            return accumulate(existing, quantity);
        }
        try {
            MallCart cart = new MallCart();
            cart.setCustomerId(customerId);
            cart.setSkuId(request.skuId());
            cart.setQuantity(quantity);
            cart.setSelected(1);
            return cartRepository.saveAndFlush(cart).getId();
        } catch (DataIntegrityViolationException ex) {
            // 并发加购:另一个请求刚插入成功(唯一键冲突)。改成累加,别让用户看到"加购失败"
            MallCart concurrent = cartRepository.findByCustomerIdAndSkuId(customerId, request.skuId())
                    .orElseThrow(() -> ex);
            return accumulate(concurrent, quantity);
        }
    }

    @Override
    public void update(Long cartId, CartUpdateRequest request) {
        MallCart cart = loadOwned(cartId);
        if (request.quantity() != null) {
            MallSku sku = skuRepository.findById(cart.getSkuId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品规格不存在"));
            requireSellable(sku);
            if (request.quantity() > sku.availableStock()) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "库存不足,当前仅剩 " + sku.availableStock() + " 件");
            }
            cart.setQuantity(request.quantity());
        }
        if (request.selected() != null) {
            cart.setSelected(request.selected() == 0 ? 0 : 1);
        }
    }

    @Override
    public void remove(List<Long> cartIds) {
        if (cartIds == null || cartIds.isEmpty()) {
            return;
        }
        Long customerId = ClientContext.requireCustomerId();
        List<MallCart> carts = cartRepository.findAllById(cartIds);
        // 只删属于当前客户的条目:传别人的 cartId 进来什么都不会发生(而不是删掉别人的)
        carts.stream().filter(cart -> Objects.equals(cart.getCustomerId(), customerId))
                .forEach(cartRepository::delete);
    }

    @Override
    public void clear() {
        Long customerId = ClientContext.requireCustomerId();
        cartRepository.deleteAll(cartRepository.findByCustomerIdOrderByIdDesc(customerId));
    }

    private Long accumulate(MallCart cart, int delta) {
        int total = cart.getQuantity() + delta;
        if (total > MAX_QUANTITY) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "单个 SKU 最多购买 " + MAX_QUANTITY + " 件");
        }
        MallSku sku = skuRepository.findById(cart.getSkuId()).orElse(null);
        if (sku != null && total > sku.availableStock()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "库存不足,当前仅剩 " + sku.availableStock() + " 件");
        }
        cart.setQuantity(total);
        // 再次加购时把条目设为选中:用户的意图是"我想买它",加进购物车却被默认不勾选会很困惑
        cart.setSelected(1);
        return cart.getId();
    }

    private void requireSellable(MallSku sku) {
        if (sku.getStatus() == null || sku.getStatus() != STATUS_ENABLED) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该规格已停售");
        }
        MallGoods goods = goodsRepository.findById(sku.getGoodsId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品不存在"));
        if (goods.getStatus() == null || goods.getStatus() != STATUS_ENABLED) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "商品已下架");
        }
        if (sku.availableStock() <= 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "商品已售罄");
        }
    }

    /** 取属于当前客户的条目;别人的条目一律按"不存在"处理(不泄露它是否存在)。 */
    private MallCart loadOwned(Long cartId) {
        Long customerId = ClientContext.requireCustomerId();
        MallCart cart = cartRepository.findById(cartId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "购物车条目不存在"));
        if (!Objects.equals(cart.getCustomerId(), customerId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "购物车条目不存在");
        }
        return cart;
    }
}
