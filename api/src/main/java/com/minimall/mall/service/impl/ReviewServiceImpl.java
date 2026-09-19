package com.minimall.mall.service.impl;

import com.minimall.mall.api.dto.ReviewCreateRequest;
import com.minimall.mall.api.dto.ReviewView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.mall.domain.MallGoodsReview;
import com.minimall.mall.domain.MallOrder;
import com.minimall.mall.domain.MallOrderItem;
import com.minimall.mall.domain.QMallGoodsReview;
import com.minimall.mall.domain.repository.MallCustomerRepository;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallGoodsReviewRepository;
import com.minimall.mall.domain.repository.MallOrderItemRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.service.ReviewService;
import com.querydsl.core.BooleanBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 商品评价实现(商城设计文档 2)。
 */
@Service
@Transactional
public class ReviewServiceImpl implements ReviewService {

    /** 展示中。 */
    private static final int STATUS_VISIBLE = 1;
    /** 已完成订单。 */
    private static final int ORDER_FINISHED = MallOrder.STATUS_FINISHED;
    private static final String ANONYMOUS_NAME = "匿名用户";

    private final MallGoodsReviewRepository reviewRepository;
    private final MallOrderRepository orderRepository;
    private final MallOrderItemRepository orderItemRepository;
    private final MallGoodsRepository goodsRepository;
    private final MallCustomerRepository customerRepository;

    public ReviewServiceImpl(MallGoodsReviewRepository reviewRepository,
                             MallOrderRepository orderRepository,
                             MallOrderItemRepository orderItemRepository,
                             MallGoodsRepository goodsRepository,
                             MallCustomerRepository customerRepository) {
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.goodsRepository = goodsRepository;
        this.customerRepository = customerRepository;
    }

    @Override
    public Long create(ReviewCreateRequest request) {
        Long customerId = ClientContext.requireCustomerId();
        MallOrderItem item = orderItemRepository.findById(request.orderItemId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单明细不存在"));
        MallOrder order = orderRepository.findById(item.getOrderId())
                .filter(value -> Objects.equals(value.getCustomerId(), customerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "订单明细不存在"));
        if (order.getStatus() == null || order.getStatus() != ORDER_FINISHED) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "订单完成后才能评价");
        }
        if (reviewRepository.existsByOrderItemId(request.orderItemId())) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "该商品已评价过");
        }

        MallGoodsReview review = new MallGoodsReview();
        review.setGoodsId(item.getGoodsId());
        review.setOrderItemId(request.orderItemId());
        review.setCustomerId(customerId);
        review.setRating(request.rating());
        review.setContent(request.content());
        review.setImages(toJsonArray(request.images()));
        review.setIsAnonymous(Boolean.TRUE.equals(request.anonymous()) ? 1 : 0);
        review.setStatus(STATUS_VISIBLE);
        return reviewRepository.save(review).getId();
    }

    @Override
    public PageResult<ReviewView> listByGoods(Long goodsId, int pageNo, int pageSize) {
        QMallGoodsReview qReview = QMallGoodsReview.mallGoodsReview;
        BooleanBuilder where = new BooleanBuilder();
        where.and(qReview.goodsId.eq(goodsId));
        where.and(qReview.status.eq(STATUS_VISIBLE));
        Page<MallGoodsReview> page = reviewRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1))
                        .withSort(Sort.by("id").descending()));
        return PageResult.of(page.getTotalElements(), toViews(page.getContent()));
    }

    @Override
    public PageResult<ReviewView> page(Long goodsId, Integer status, int pageNo, int pageSize) {
        QMallGoodsReview qReview = QMallGoodsReview.mallGoodsReview;
        BooleanBuilder where = new BooleanBuilder();
        if (goodsId != null) {
            where.and(qReview.goodsId.eq(goodsId));
        }
        if (status != null) {
            where.and(qReview.status.eq(status));
        }
        Page<MallGoodsReview> page = reviewRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1))
                        .withSort(Sort.by("id").descending()));
        return PageResult.of(page.getTotalElements(), toViews(page.getContent()));
    }

    @Override
    public void reply(Long reviewId, String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "回复内容不能为空");
        }
        MallGoodsReview review = load(reviewId);
        review.setReplyContent(content);
        review.setReplyTime(LocalDateTime.now());
    }

    @Override
    public void changeStatus(Long reviewId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "状态只能是 0(隐藏)或 1(展示)");
        }
        load(reviewId).setStatus(status);
    }

    // ------------------------------------------------------------------ 内部

    private List<ReviewView> toViews(List<MallGoodsReview> reviews) {
        if (reviews.isEmpty()) {
            return List.of();
        }
        Map<Long, String> goodsNames = new HashMap<>();
        goodsRepository.findAllById(reviews.stream().map(MallGoodsReview::getGoodsId).distinct().toList())
                .forEach(goods -> goodsNames.put(goods.getId(), goods.getGoodsName()));
        Map<Long, String> nicknames = new HashMap<>();
        customerRepository.findAllById(reviews.stream().map(MallGoodsReview::getCustomerId).distinct().toList())
                .forEach(customer -> nicknames.put(customer.getId(), customer.getNickname()));
        return reviews.stream().map(review -> new ReviewView(review.getId(), review.getGoodsId(),
                goodsNames.get(review.getGoodsId()),
                // 匿名在这里就替换掉,不把真实昵称交给端上"自行隐藏"
                Objects.equals(review.getIsAnonymous(), 1) ? ANONYMOUS_NAME
                        : nicknames.getOrDefault(review.getCustomerId(), "用户"),
                review.getRating(), review.getContent(), review.getImages(), review.getIsAnonymous(),
                review.getReplyContent(), review.getReplyTime(), review.getStatus(), review.getCreateTime()))
                .toList();
    }

    /** 图片列表存成 JSON 数组字符串(与实体注释一致),避免为几张图再建一张表。 */
    private String toJsonArray(List<String> images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        return images.stream()
                .filter(url -> url != null && !url.isBlank())
                .map(url -> "\"" + url.trim().replace("\"", "") + "\"")
                .reduce((a, b) -> a + "," + b)
                .map(body -> "[" + body + "]")
                .orElse(null);
    }

    private MallGoodsReview load(Long reviewId) {
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "评价不存在"));
    }
}
