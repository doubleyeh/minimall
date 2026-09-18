package com.minimall.domain;

/**
 * "有归属人"的业务实体标记(架构文档 5.3)。
 *
 * <p>标记它的实体,{@code create_by} 的语义从"审计字段"升级为"<b>数据归属人</b>":
 * 写入时必须非空,取不到审计上下文就抛异常阻断,而不是写入 {@code create_by = null} 的脏数据。
 *
 * <p>为什么必须有这条硬约束:{@code data_scope = 1}(仅本人)的条件就是 {@code create_by = 当前用户ID},
 * 一条归属人为空的数据在当前模型下**对所有人都不可见**——与其静默写入一条谁也看不到的记录,
 * 不如在写入时就失败。代价是:定时任务/支付回调创建这类数据时,必须显式带上 {@link com.minimall.infra.audit.AuditContext} 快照(见 4.12)。
 *
 * <p>当前打标的是 {@code SysUser};以后的业务表(订单、商品等)按需打标,
 * 打标前先确认 4.6.1 的"数据权限"一栏是否真的需要它。
 */
public interface OwnedEntity {
}
