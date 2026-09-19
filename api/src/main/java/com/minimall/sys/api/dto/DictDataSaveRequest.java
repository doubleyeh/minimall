package com.minimall.sys.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 字典数据新增/修改请求(架构文档 5.1)。
 *
 * <p>{@code dictType} 必须是已存在的字典类型编码(服务端校验),否则会产出一条永远查不到的孤儿字典项。
 */
public record DictDataSaveRequest(
        @NotBlank(message = "字典类型编码不能为空")
        @Size(max = 64, message = "字典类型编码不能超过 64 个字符")
        String dictType,

        @NotBlank(message = "字典标签不能为空")
        @Size(max = 64, message = "字典标签不能超过 64 个字符")
        String dictLabel,

        @NotBlank(message = "字典值不能为空")
        @Size(max = 64, message = "字典值不能超过 64 个字符")
        String dictValue,

        Integer sortOrder) {
}
