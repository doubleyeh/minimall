package com.minimall.api.mall.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 满减活动新增/修改(商城设计文档 3.5、3.10)。
 *
 * @param reductionRule 阶梯规则 JSON,如 {@code [{"amount":100,"reduce":10},{"amount":200,"reduce":30}]}。
 *                      匹配时**按门槛取满足条件的最高档**,所以书写顺序不影响结果(顺序写反也能算对)
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
