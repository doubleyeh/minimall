package com.minimall.mall.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 满减活动新增/修改(商城设计文档 3.5、3.10)。
 *
 * @param reductionRule 阶梯规则 JSON,如 {@code [{"amount":100,"reduce":10},{"amount":200,"reduce":30}]}。
 *                      保存时校验:每档 {@code reduce} 必须小于 {@code amount},且档位按两者**双递增**
 *                      ({@code amount} 与 {@code reduce} 都要比上一档大)。匹配时取满足门槛里最高的那一档,
 *                      所以历史数据即使写成反序也仍能算对
 * @param scopeIds      {@code scopeType = 2/3} 时的分类/商品 ID;{@code = 1}(全部商品)时可为空
 */
public record PromotionSaveRequest(
        @NotBlank(message = "活动名称不能为空")
        @Size(max = 64, message = "活动名称不能超过 64 个字符")
        String activityName,

        @NotBlank(message = "满减规则不能为空")
        String reductionRule,

        @NotNull(message = "适用范围不能为空")
        Integer scopeType,

        List<Long> scopeIds,

        @NotNull(message = "开始时间不能为空")
        LocalDateTime validStartTime,

        @NotNull(message = "结束时间不能为空")
        LocalDateTime validEndTime,

        Integer status) {
}
