package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.api.dto.ReviewCreateRequest;
import com.minimall.mall.api.dto.ReviewView;
import com.minimall.mall.domain.MallOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品评价服务(商城设计文档 2)。
 *
 * <p>两条入口规则都在这里定死:订单**必须已完成**,且同一订单明细**只能评价一次**。
 * 它们不能只靠端上隐藏按钮来保证 —— 接口是公开的,直接调就会绕过去。
 */
class ReviewServiceIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private ReviewService reviewService;

    /** 造一条"已完成订单"的明细 id(评价的前置条件)。 */
    private Long finishedOrderItemId(Long ownerCustomerId) {
        OrderCreateResponse order = createOrder(ownerCustomerId, addressId, 1);
        forceOrderStatus(order.orderId(), MallOrder.STATUS_FINISHED);
        return inTenant(() -> orderItemRepository.findByOrderIdOrderByIdAsc(order.orderId()).get(0).getId());
    }

    private static ReviewCreateRequest request(Long orderItemId, int rating, String content) {
        return new ReviewCreateRequest(orderItemId, rating, content, null, null);
    }

    @Test
    @DisplayName("订单未完成不能评价(接口是公开的,不能只靠端上藏按钮)")
    void createRejectsUnfinishedOrder() {
        OrderCreateResponse order = createOrder(customerId, addressId, 1);
        Long itemId = inTenant(() -> orderItemRepository.findByOrderIdOrderByIdAsc(order.orderId()).get(0).getId());

        assertThatThrownBy(() -> asClient(customerId, () -> reviewService.create(request(itemId, 5, "很好"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单完成后才能评价");
    }

    @Test
    @DisplayName("已完成订单可评价:落库并带商品快照与匿名标记")
    void createStoresReview() {
        Long itemId = finishedOrderItemId(customerId);

        Long reviewId = asClient(customerId, () -> reviewService.create(
                new ReviewCreateRequest(itemId, 4, "还不错", List.of("https://a.png", " ", "https://b.png"), true)));

        inTenant(() -> {
            var review = reviewRepository.findById(reviewId).orElseThrow();
            assertThat(review.getGoodsId()).isEqualTo(goodsId);
            assertThat(review.getOrderItemId()).isEqualTo(itemId);
            assertThat(review.getCustomerId()).isEqualTo(customerId);
            assertThat(review.getRating()).isEqualTo(4);
            assertThat(review.getIsAnonymous()).isEqualTo(1);
            assertThat(review.getStatus()).as("新评价默认展示中").isEqualTo(1);
            assertThat(review.getImages())
                    .as("图片存成 JSON 数组字符串,且要丢掉空白项")
                    .isEqualTo("[\"https://a.png\",\"https://b.png\"]");
            return null;
        });
    }

    @Test
    @DisplayName("没有图片时存 null,而不是空数组字符串")
    void createWithoutImagesStoresNull() {
        Long reviewId = asClient(customerId, () -> reviewService.create(
                new ReviewCreateRequest(finishedOrderItemId(customerId), 5, "好", List.of("  "), false)));

        inTenant(() -> {
            assertThat(reviewRepository.findById(reviewId).orElseThrow().getImages()).isNull();
            return null;
        });
    }

    @Test
    @DisplayName("同一订单明细重复评价被拒")
    void createRejectsDuplicate() {
        Long itemId = finishedOrderItemId(customerId);
        asClient(customerId, () -> reviewService.create(request(itemId, 5, "很好")));

        assertThatThrownBy(() -> asClient(customerId, () -> reviewService.create(request(itemId, 1, "改口了"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DATA_CONFLICT));
    }

    @Test
    @DisplayName("别人的订单明细:按不存在处理")
    void createRejectsOthersOrderItem() {
        // 另一个客户要下单就得有自己的收货地址:地址是按客户隔离的,借用主客户的会被下单入口拒绝
        Long othersAddressId = inTenant(() -> newAddress(otherCustomerId, "别人"));
        OrderCreateResponse order = createOrder(otherCustomerId, othersAddressId, 1);
        forceOrderStatus(order.orderId(), MallOrder.STATUS_FINISHED);
        Long othersItemId = inTenant(() ->
                orderItemRepository.findByOrderIdOrderByIdAsc(order.orderId()).get(0).getId());

        assertThatThrownBy(() -> asClient(customerId, () -> reviewService.create(request(othersItemId, 5, "刷单"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("不存在的订单明细:NOT_FOUND")
    void createRejectsUnknownItem() {
        assertThatThrownBy(() -> asClient(customerId, () -> reviewService.create(request(999999L, 5, "x"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    @DisplayName("商品评价列表:只返回展示中的,被隐藏的不出现")
    void listByGoodsReturnsVisibleOnly() {
        Long visibleId = asClient(customerId, () -> reviewService.create(
                request(finishedOrderItemId(customerId), 5, "看得见")));
        Long hiddenId = asClient(customerId, () -> reviewService.create(
                request(finishedOrderItemId(customerId), 1, "被隐藏了")));

        // 评价列表是公开接口,但**仍然要有租户上下文**(游客请求靠 X-Tenant-Code 定位商家)。
        // 上下文为空时租户过滤器会绑哨兵值,查询返回空集 —— 那看起来像"没有评价",而不是报错。
        PageResult<ReviewView> before = inTenant(() -> reviewService.listByGoods(goodsId, 1, 10));
        assertThat(before.total()).isEqualTo(2);

        // 管理端的隐藏入口(changeStatus)也要真的影响端上能看到什么
        inTenant(() -> {
            reviewService.changeStatus(hiddenId, 0);
            return null;
        });

        PageResult<ReviewView> after = inTenant(() -> reviewService.listByGoods(goodsId, 1, 10));
        assertThat(after.total()).isEqualTo(1);
        assertThat(after.list().get(0).id()).isEqualTo(visibleId);
        assertThat(after.list().get(0).content()).isEqualTo("看得见");
        assertThat(after.list().get(0).goodsName()).as("列表要带上商品名").isEqualTo("集成测试商品");
    }

    @Test
    @DisplayName("匿名评价:视图里直接换成匿名昵称,不把真实昵称交给端上自行隐藏")
    void anonymousReviewHidesNickname() {
        Long reviewId = asClient(customerId, () -> reviewService.create(
                new ReviewCreateRequest(finishedOrderItemId(customerId), 5, "匿名的", null, true)));

        PageResult<ReviewView> page = inTenant(() -> reviewService.listByGoods(goodsId, 1, 10));
        ReviewView view = page.list().stream().filter(item -> item.id().equals(reviewId)).findFirst().orElseThrow();
        assertThat(view.customerNickname()).isEqualTo("匿名用户");
        assertThat(view.isAnonymous()).isEqualTo(1);
    }

    @Test
    @DisplayName("管理端:回复空内容被拒、状态只接受 0/1")
    void adminOperationsValidateInput() {
        Long reviewId = asClient(customerId, () -> reviewService.create(
                request(finishedOrderItemId(customerId), 5, "待回复")));

        assertThatThrownBy(() -> inTenant(() -> {
            reviewService.reply(reviewId, "   ");
            return null;
        })).isInstanceOf(BusinessException.class).hasMessageContaining("回复内容不能为空");

        assertThatThrownBy(() -> inTenant(() -> {
            reviewService.changeStatus(reviewId, 5);
            return null;
        })).isInstanceOf(BusinessException.class).hasMessageContaining("状态只能是");

        inTenant(() -> {
            reviewService.reply(reviewId, "感谢支持");
            return null;
        });
        inTenant(() -> {
            var review = reviewRepository.findById(reviewId).orElseThrow();
            assertThat(review.getReplyContent()).isEqualTo("感谢支持");
            assertThat(review.getReplyTime()).isNotNull();
            return null;
        });
    }
}
