package com.minimall.mall.service;

import com.minimall.mall.api.dto.MemberLevelSaveRequest;
import com.minimall.mall.api.dto.MemberLevelView;

import java.util.List;

/**
 * 会员等级(商城设计文档 3.11)。只管等级定义的维护。
 *
 * <p>**晋升与降级不在这里**,由 {@link MemberPointsService} 按滚动成长值算 ——
 * 它读的是 {@code level_sort} 之外的 {@code growth_threshold},所以本接口只管增改查。
 *
 * <p>{@code discountRate} 仍然只存不用:等级折扣与优惠券/满减的叠加顺序尚未确定(开放项 2)。
 */
public interface MemberLevelService {

    List<MemberLevelView> list();

    Long create(MemberLevelSaveRequest request);

    void update(Long levelId, MemberLevelSaveRequest request);
}
