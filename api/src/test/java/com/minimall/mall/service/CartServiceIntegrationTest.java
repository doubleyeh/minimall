package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.api.dto.CartAddRequest;
import com.minimall.mall.api.dto.CartItemView;
import com.minimall.mall.api.dto.CartUpdateRequest;
import com.minimall.mall.domain.MallCart;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallSku;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 购物车服务(商城设计文档 2)。
 *
 * <p>这一层覆盖的规则都不在"正常加购"那条路上,而在几个边界上:重复加购必须累加(不然购物车会出现
 * 两行同样的商品)、数量与可售库存的关系、以及"别人的购物车条目动不了"。
 */
class CartServiceIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private CartService cartService;

    @Test
    @DisplayName("加购:落库并默认勾选")
    void addCreatesSelectedEntry() {
        Long cartId = asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 2)));

        inTenant(() -> {
            MallCart cart = cartRepository.findById(cartId).orElseThrow();
            assertThat(cart.getCustomerId()).isEqualTo(customerId);
            assertThat(cart.getQuantity()).isEqualTo(2);
            // 用户的意图就是"想买它",加进购物车却默认不勾选会很困惑
            assertThat(cart.getSelected()).isEqualTo(1);
            return null;
        });
    }

    @Test
    @DisplayName("重复加购:累加到同一行而不是新增条目")
    void addAccumulatesInsteadOfDuplicating() {
        Long first = asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 2)));
        Long second = asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 3)));

        assertThat(second).as("第二次加购返回同一条目").isEqualTo(first);
        inTenant(() -> {
            assertThat(cartRepository.findByCustomerIdOrderByIdDesc(customerId)).hasSize(1);
            assertThat(cartRepository.findById(first).orElseThrow().getQuantity()).isEqualTo(5);
            return null;
        });
    }

    @Test
    @DisplayName("加购数量超过可售库存被拒(累加与首次插入都要拦)")
    void addRejectsOverStock() {
        // 夹具里 SKU 库存 10
        assertThatThrownBy(() -> asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 11))))
                .as("首次加购也必须校验库存,否则购物车里会留下一个永远无法结算的数量")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");

        asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 8)));
        assertThatThrownBy(() -> asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 3))))
                .as("累加后超过库存同样要拒绝")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");
    }

    @Test
    @DisplayName("累加超过单 SKU 上限被拒")
    void addRejectsOverMaxQuantity() {
        // 单 SKU 上限与库存是两条独立规则:要验上限,先把库存抬到不构成约束
        inTenant(() -> {
            jdbcTemplate.update("UPDATE mall_sku SET stock = 2000, locked_stock = 0 WHERE id = ?", skuId);
            return null;
        });
        asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 999)));

        assertThatThrownBy(() -> asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多购买");
    }

    @Test
    @DisplayName("规格不存在 / 已停售 / 商品已下架 / 已售罄 都不能加购")
    void addRejectsUnsellable() {
        Long unknownSkuId = 999999L;
        assertThatThrownBy(() -> asClient(customerId, () -> cartService.add(new CartAddRequest(unknownSkuId, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("商品规格不存在");

        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            sku.setStatus(0);
            skuRepository.save(sku);
            return null;
        });
        assertThatThrownBy(() -> asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 1))))
                .hasMessageContaining("已停售");

        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            sku.setStatus(1);
            skuRepository.save(sku);
            MallGoods goods = goodsRepository.findById(goodsId).orElseThrow();
            goods.setStatus(0);
            goodsRepository.save(goods);
            return null;
        });
        assertThatThrownBy(() -> asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 1))))
                .hasMessageContaining("商品已下架");

        inTenant(() -> {
            MallGoods goods = goodsRepository.findById(goodsId).orElseThrow();
            goods.setStatus(1);
            goodsRepository.save(goods);
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            sku.setStock(0);
            sku.setLockedStock(0);
            skuRepository.save(sku);
            return null;
        });
        assertThatThrownBy(() -> asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 1))))
                .hasMessageContaining("已售罄");
    }

    @Test
    @DisplayName("改数量超过可售库存被拒,且原数量保持不变")
    void updateRejectsOverStock() {
        Long cartId = asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 2)));

        assertThatThrownBy(() -> asClientRun(customerId, () -> cartService.update(cartId, new CartUpdateRequest(11, null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足");

        inTenant(() -> {
            assertThat(cartRepository.findById(cartId).orElseThrow().getQuantity())
                    .as("被拒后数量不能被改坏")
                    .isEqualTo(2);
            return null;
        });
    }

    @Test
    @DisplayName("勾选状态只接受 0/1,其它值一律按选中处理(不去猜调用方的意图)")
    void updateNormalizesSelected() {
        Long cartId = asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 1)));

        asClientRun(customerId, () -> cartService.update(cartId, new CartUpdateRequest(null, 0)));
        inTenant(() -> {
            assertThat(cartRepository.findById(cartId).orElseThrow().getSelected()).isZero();
            return null;
        });

        asClientRun(customerId, () -> cartService.update(cartId, new CartUpdateRequest(null, 7)));
        inTenant(() -> {
            assertThat(cartRepository.findById(cartId).orElseThrow().getSelected())
                    .as("非 0 的取值都归一成选中")
                    .isEqualTo(1);
            return null;
        });
    }

    @Test
    @DisplayName("删别人的条目:什么都不发生(而不是删掉别人的)")
    void removeIgnoresEntriesOfOthers() {
        Long othersCartId = asClient(otherCustomerId, () -> cartService.add(new CartAddRequest(skuId, 2)));

        asClientRun(customerId, () -> cartService.remove(List.of(othersCartId)));

        inTenant(() -> {
            assertThat(cartRepository.findById(othersCartId))
                    .as("别人的条目必须还在")
                    .isPresent();
            return null;
        });
    }

    @Test
    @DisplayName("空列表删除是合法请求,不报错")
    void removeEmptyIsNoop() {
        assertThatCode(() -> asClientRun(customerId, () -> cartService.remove(Collections.emptyList())))
                .doesNotThrowAnyException();
        assertThatCode(() -> asClientRun(customerId, () -> cartService.remove(null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("清空只清自己的购物车")
    void clearOnlyClearsOwnEntries() {
        asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 1)));
        Long othersCartId = asClient(otherCustomerId, () -> cartService.add(new CartAddRequest(skuId, 1)));

        asClientRun(customerId, () -> cartService.clear());

        inTenant(() -> {
            assertThat(cartRepository.findByCustomerIdOrderByIdDesc(customerId)).isEmpty();
            assertThat(cartRepository.findById(othersCartId)).isPresent();
            return null;
        });
    }

    @Test
    @DisplayName("商品下架后条目仍在列表里,但标记为不可结算")
    void listKeepsInvalidEntryButMarksItUnsettleable() {
        Long cartId = asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 1)));
        inTenant(() -> {
            MallGoods goods = goodsRepository.findById(goodsId).orElseThrow();
            goods.setStatus(0);
            goodsRepository.save(goods);
            return null;
        });

        List<CartItemView> items = asClient(customerId, () -> cartService.list());

        assertThat(items).hasSize(1);
        CartItemView item = items.get(0);
        assertThat(item.id()).isEqualTo(cartId);
        assertThat(item.goodsName()).as("下架也要能显示是哪个商品").isNotBlank();
        assertThat(item.valid()).as("下架/售罄的条目要在端上置灰,由结算自动跳过").isFalse();
    }

    @Test
    @DisplayName("空购物车返回空列表(不查 SKU/商品)")
    void listOfEmptyCartIsEmpty() {
        assertThat(asClient(customerId, () -> cartService.list())).isEmpty();
    }

    @Test
    @DisplayName("SKU 已被删除时条目仍能返回,只是不可结算(而不是整个购物车报错)")
    void listToleratesDeletedSku() {
        Long cartId = asClient(customerId, () -> cartService.add(new CartAddRequest(skuId, 1)));
        // 必须用 JdbcTemplate 直接改:在测试的非事务上下文里读到的是游离态实体,改它不会落库
        // (第一次写成 repository.findById(...).ifPresent(cart -> cart.setSkuId(...)) 就没生效)
        inTenant(() -> {
            jdbcTemplate.update("UPDATE mall_cart SET sku_id = ? WHERE id = ?", 999999L, cartId);
            return null;
        });

        List<CartItemView> items = asClient(customerId, () -> cartService.list());
        assertThat(items).hasSize(1);
        assertThat(items.get(0).valid()).isFalse();
    }

    @Test
    @DisplayName("改别人的条目按不存在处理(不泄露它是否存在)")
    void updateOthersEntryIsNotFound() {
        Long othersCartId = asClient(otherCustomerId, () -> cartService.add(new CartAddRequest(skuId, 1)));

        assertThatThrownBy(() -> asClientRun(customerId,
                () -> cartService.update(othersCartId, new CartUpdateRequest(5, null))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }
}
