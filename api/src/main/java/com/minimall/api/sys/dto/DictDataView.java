package com.minimall.api.sys.dto;

/**
 * 字典数据项(管理端维护用,含主键,可编辑可删除)。
 */
public record DictDataView(
        Long id,
        String dictType,
        String dictLabel,
        String dictValue,
        Integer sortOrder) {
}
