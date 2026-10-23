package com.minimall.mall.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动保存请求(管理端)。
 *
 * <p>草稿阶段只存配置、不冻库存;冻结发生在发布(见 {@code SeckillService#publish})。
 * 这样商家可以先慢慢配,不会配一半就把货锁住。
 */
public record SeckillActivitySaveRequest(
        @NotBlank String activityName,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime,
        String remark,
        Integer sortOrder,
        @NotEmpty @Valid List<Item> items) {

    /**
     * 一个商品项。{@code skuId} 决定商品与规格,库存上限取自该 SKU 的可售库存。
     */
    public record Item(
            @NotNull Long skuId,
            @NotNull java.math.BigDecimal seckillPrice,
            @NotNull Integer seckillStock,
            @NotNull Integer perCustomerLimit,
            Integer sortOrder) {
    }
}
