package com.minimall.sys.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 修改密码请求(架构文档 7.1.2)。
 *
 * <p>密码复杂度规则集中在这里与 7.1.2 节的约定保持一致:长度 8-32,必须同时含字母和数字。
 * 现阶段不做特殊字符/弱密码字典校验,原因见 7.1.2(把弱密码问题变成"用户把密码写在便签上"是更常见的失败方式)。
 */
public record ChangePasswordRequest(

        @NotBlank(message = "原密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,32}$",
                message = "新密码需为8-32位且同时包含字母和数字")
        String newPassword
) {
}
