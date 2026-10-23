package com.minimall.mall.service;

import com.minimall.common.PageResult;
import com.minimall.mall.api.dto.SeckillActivitySaveRequest;
import com.minimall.mall.api.dto.SeckillActivityView;

/**
 * 秒杀活动管理(商城设计文档 3.12)。
 *
 * <p>草稿可改可删、不冻库存;发布一次性校验并冻结;**发布后不可编辑** ——
 * 改配额等于改已经卖出去的账,只能取消后重建。
 */
public interface SeckillService {

    PageResult<SeckillActivityView> page(String keyword, Integer status, int pageNo, int pageSize);

    SeckillActivityView detail(Long activityId);

    Long create(SeckillActivitySaveRequest request);

    void update(Long activityId, SeckillActivitySaveRequest request);

    void delete(Long activityId);

    /** 发布:校验 + 冻结库存 + 置为待开始,返回冻结总件数。 */
    int publish(Long activityId);

    /** 取消:未开始/进行中的活动作废,未售出的配额解冻。 */
    void cancel(Long activityId);

    /** 到点开始(定时任务),返回本批推进的活动数。 */
    int startDue();

    /** 到点结束(定时任务),返回本批推进的活动数。 */
    int endDue();
}
