package com.minimall.common;

import java.util.List;

/**
 * 统一分页体(架构文档 7.3):分页格式统一,所有列表接口都用它,前端只写一套列表组件。
 */
public record PageResult<T>(long total, List<T> list) {

    public static <T> PageResult<T> of(long total, List<T> list) {
        return new PageResult<>(total, list);
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(0L, List.of());
    }
}
