package com.minimall.mall.infra.auth;

/**
 * 客户端身份(商城设计文档 3.1)。
 *
 * <p><b>与后台身份是两套东西,不要混用</b>:后台身份是 {@code sys_user.id}(走 Sa-Token 会话),
 * 客户端身份是 {@code mall_customer.id}(走 JWT)。两者可能数字相同,但语义完全不同 ——
 * 用错的表现是"越权读到了别人的数据"或"订单查不到"。
 *
 * @param tenantId   该客户所属租户(小程序),用于设置租户上下文
 * @param customerId 客户 ID
 */
public record ClientPrincipal(Long tenantId, Long customerId) {
}
