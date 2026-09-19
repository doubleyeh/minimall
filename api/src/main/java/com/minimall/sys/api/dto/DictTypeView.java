package com.minimall.sys.api.dto;

import java.time.LocalDateTime;

/**
 * 字典类型列表项(架构文档 7.3:对外结构用 View 表达,不直接把实体丢给前端)。
 */
public record DictTypeView(
        Long id,
        String dictType,
        String dictName,
        /** 该类型下已配置的字典项数量,列表页直接展示,免得前端逐个类型再查一次 */
        long dataCount,
        LocalDateTime createTime) {
}
