package com.minimall.mall.infra.pay;

import tools.jackson.databind.JsonNode;

/**
 * 回调解密后的结果。
 *
 * @param tenantId   由路径上的租户编码定位 —— 回调体是密文,没有它就不知道用哪把密钥解密
 * @param eventType  事件类型,如 {@code TRANSACTION.SUCCESS}、{@code REFUND.CLOSED}
 * @param resource   解密后的业务对象(JSON)
 */
public record WxPayNotify(Long tenantId, String eventType, JsonNode resource) {
}
