package com.minimall.mall.service;

import com.minimall.mall.api.dto.MemberLevelSaveRequest;
import com.minimall.mall.api.dto.MemberLevelView;

import java.util.List;

/**
 * 会员等级(商城设计文档 5.2)。
 *
 * <p>本期只做维护(等级定义),**不做晋升与折扣计算** —— 那部分的计算规则尚未确定,
 * 字段先建好,规则明确后再补(见文档开放项 2)。
 */
public interface MemberLevelService {

    List<MemberLevelView> list();

    Long create(MemberLevelSaveRequest request);

    void update(Long levelId, MemberLevelSaveRequest request);
}
