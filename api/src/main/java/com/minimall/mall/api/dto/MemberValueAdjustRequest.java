package com.minimall.mall.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端手动调整积分与成长值(商城设计文档 3.11)。
 *
 * <p>调整量正负均可(补分与追回都是常见需求),但不能同时为 0 —— 那是一次什么都没做的操作。
 * {@code remark} 必填:手工改动资产必须留下"为什么",否则事后对账无从解释。
 */
public record MemberValueAdjustRequest(
        Integer pointsDelta,

        Integer growthDelta,

        @NotBlank(message = "请填写调整原因")
        @Size(max = 255, message = "调整原因不能超过 255 个字符")
        String remark) {
}
