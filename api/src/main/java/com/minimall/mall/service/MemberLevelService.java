package com.minimall.mall.service;

import com.minimall.mall.api.dto.MemberLevelSaveRequest;
import com.minimall.mall.api.dto.MemberLevelView;

import java.util.List;

/**
 * 会员等级(商城设计文档 3.11)。只管等级定义的维护。
 *
 * <p>**晋升与降级不在这里**,由 {@link MemberPointsService} 按滚动成长值算 ——
 * 它读的是 {@code level_sort} 之外的 {@code growth_threshold},所以本接口只管增改查。
 */
public interface MemberLevelService {

    List<MemberLevelView> list();

    Long create(MemberLevelSaveRequest request);

    void update(Long levelId, MemberLevelSaveRequest request);

    /**
     * 等级 id → 展示名。传 null 或查不到时回退 {@link com.minimall.mall.domain.MallMemberLevel#DEFAULT_LEVEL_NAME}。
     *
     * <p>放在这里是因为有两个调用方(小程序个人中心、管理端客户列表),各写一份的话
     * 回退文案迟早会不一致 —— 那正是"同一个人在两处看到不同等级名"的来源。
     */
    String displayName(Long levelId);

    /** 还差多少成长值升到下一级;已是最高等级(或本租户还没建任何等级)时返回 null。 */
    Integer growthToNextLevel(int growth);
}
