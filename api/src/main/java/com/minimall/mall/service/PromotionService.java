package com.minimall.mall.service;

import com.minimall.mall.api.dto.PromotionSaveRequest;
import com.minimall.mall.api.dto.PromotionView;
import com.minimall.common.PageResult;

/**
 * 满减活动(商城设计文档 3.5、3.10)。
 *
 * <p>本期只支持"包含"逻辑(活动作用于全部商品 / 指定分类 / 指定商品),
 * **不支持排除**(如"全部商品参与,除 XX 分类外")—— 见文档开放项 1。
 */
public interface PromotionService {

    PageResult<PromotionView> page(String activityName, Integer status, int pageNo, int pageSize);

    Long create(PromotionSaveRequest request);

    void update(Long activityId, PromotionSaveRequest request);

    void changeStatus(Long activityId, Integer status);
}
