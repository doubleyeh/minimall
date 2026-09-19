package com.minimall.api.sys.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 字典类型新增/修改请求(架构文档 5.1)。
 *
 * <p>修改时 {@code dictType} 只允许与库里一致:改类型编码会让已有的 {@code sys_dict_data.dictType} 悬空,
 * 历史数据里凡是按老编码写入的取值都会查不到字典。要换编码请新建类型再迁移数据。
 */
public record DictTypeSaveRequest(
        @NotBlank(message = "字典类型编码不能为空")
        @Size(max = 64, message = "字典类型编码不能超过 64 个字符")
        String dictType,

        @NotBlank(message = "字典类型名称不能为空")
        @Size(max = 64, message = "字典类型名称不能超过 64 个字符")
        String dictName) {
}
