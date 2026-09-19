package com.minimall.mall.service;

import com.minimall.api.mall.dto.FreightTemplateSaveRequest;
import com.minimall.api.mall.dto.FreightTemplateView;

import java.util.List;

/**
 * 运费模板(商城设计文档 3.7)。
 *
 * <p>有一条硬性校验在服务端:**必须存在 {@code region = ALL} 的兜底规则**。
 * 没有它时,收货地址落在任何未配置的省份都会算不出运费(计算器只能按 0 处理,等于白送运费)。
 * 与其在每个下单请求上兜底,不如在保存模板时就挡住。
 */
public interface FreightTemplateService {

    List<FreightTemplateView> list();

    FreightTemplateView detail(Long templateId);

    Long create(FreightTemplateSaveRequest request);

    void update(Long templateId, FreightTemplateSaveRequest request);

    /** 删除模板:被商品引用时拒绝(不做静默解绑,否则那些商品会突然变成包邮)。 */
    void delete(Long templateId);
}
