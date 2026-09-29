package com.minimall.mall.api.dto;

/**
 * 会员等级(管理端)。
 */
public record MemberLevelView(
        Long id,
        String levelName,
        Integer levelSort,
        Integer growthThreshold,
        Integer status) {
}
