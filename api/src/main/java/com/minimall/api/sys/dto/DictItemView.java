package com.minimall.api.sys.dto;

/**
 * 字典下拉项(业务端只读用)。
 *
 * <p>刻意**不暴露主键**、只给 {@code label}/{@code value}:业务端拿字典是为了渲染下拉与翻译取值,
 * 不需要也不应该知道字典项的主键 —— 给了主键,前端就会忍不住去"按 ID 改字典项",
 * 而字典是平台级配置(4.6.1),租户侧根本没有维护它的权限。
 */
public record DictItemView(String label, String value) {
}
