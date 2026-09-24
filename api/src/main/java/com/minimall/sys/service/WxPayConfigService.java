package com.minimall.sys.service;

import com.minimall.common.PageResult;
import com.minimall.sys.api.dto.WxPayConfigSaveRequest;
import com.minimall.sys.api.dto.WxPayConfigView;

/**
 * 租户微信支付配置的管理(平台级)。
 */
public interface WxPayConfigService {

    /** 列表:列出所有租户并带上"是否已配置支付",便于发现漏配的租户。 */
    PageResult<WxPayConfigView> page(String tenantCode, Integer status, int pageNo, int pageSize);

    WxPayConfigView get(Long tenantId);

    Long create(WxPayConfigSaveRequest request);

    /** 更新;请求里为空的密钥字段保持原值。 */
    void update(Long tenantId, WxPayConfigSaveRequest request);

    void updateStatus(Long tenantId, Integer status);
}
