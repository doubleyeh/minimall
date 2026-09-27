package com.minimall.sys.service;

import com.minimall.common.PageResult;
import com.minimall.sys.api.dto.OperLogQuery;
import com.minimall.sys.api.dto.OperLogView;

import java.util.List;

/**
 * 操作日志查询(架构文档 7.2)。
 *
 * <p>**只读**:日志是审计凭据,不提供修改与删除接口 —— 能改的审计日志等于没有审计。
 */
public interface OperLogService {

    PageResult<OperLogView> page(OperLogQuery query);

    /**
     * 导出用的明细,不分页但有上限。
     *
     * <p>超过 {@code limit} 条时**报错让调用方缩小范围,而不是静默截断** ——
     * 静默截断的导出最危险:拿到文件的人会以为"就这么多",而缺的正好是问题最多的一段。
     */
    List<OperLogView> listForExport(OperLogQuery query, int limit);
}
