package com.minimall.sys.service;

import com.minimall.common.PageResult;
import com.minimall.sys.api.dto.OperLogQuery;
import com.minimall.sys.api.dto.OperLogView;

/**
 * 操作日志查询(架构文档 7.2)。
 *
 * <p>**只读**:日志是审计凭据,不提供修改与删除接口 —— 能改的审计日志等于没有审计。
 */
public interface OperLogService {

    PageResult<OperLogView> page(OperLogQuery query);
}
