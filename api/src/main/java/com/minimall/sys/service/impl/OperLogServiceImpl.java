package com.minimall.sys.service.impl;

import com.minimall.common.PageResult;
import com.minimall.sys.api.dto.OperLogQuery;
import com.minimall.sys.api.dto.OperLogView;
import com.minimall.sys.domain.QSysOperLog;
import com.minimall.sys.domain.SysOperLog;
import com.minimall.sys.domain.repository.SysOperLogRepository;
import com.minimall.sys.service.OperLogService;
import com.querydsl.core.BooleanBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 操作日志查询实现(架构文档 7.2)。
 *
 * <p>按创建时间倒序:排查问题看的是"最近发生了什么"。
 * 租户隔离不在这里判:查询走实体的租户过滤,超管身份由上下文豁免(4.6.1)。
 */
@Service
@Transactional(readOnly = true)
public class OperLogServiceImpl implements OperLogService {

    private final SysOperLogRepository operLogRepository;

    public OperLogServiceImpl(SysOperLogRepository operLogRepository) {
        this.operLogRepository = operLogRepository;
    }

    @Override
    public PageResult<OperLogView> page(OperLogQuery query) {
        QSysOperLog qLog = QSysOperLog.sysOperLog;
        BooleanBuilder where = new BooleanBuilder();
        if (query.tenantId() != null) {
            where.and(qLog.tenantId.eq(query.tenantId()));
        }
        if (query.userId() != null) {
            where.and(qLog.userId.eq(query.userId()));
        }
        if (query.module() != null && !query.module().isBlank()) {
            where.and(qLog.module.contains(query.module()));
        }
        if (query.status() != null) {
            where.and(qLog.status.eq(query.status()));
        }
        if (query.startTime() != null) {
            where.and(qLog.createTime.goe(query.startTime()));
        }
        if (query.endTime() != null) {
            where.and(qLog.createTime.loe(query.endTime()));
        }
        PageRequest pageable = PageRequest.of(
                Math.max(query.pageNo() - 1, 0),
                Math.max(query.pageSize(), 1),
                Sort.by(Sort.Direction.DESC, "createTime").and(Sort.by(Sort.Direction.DESC, "id")));

        Page<SysOperLog> page = operLogRepository.findAll(where, pageable);
        List<OperLogView> views = page.getContent().stream().map(OperLogServiceImpl::toView).toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    private static OperLogView toView(SysOperLog log) {
        return new OperLogView(log.getId(), log.getTenantId(), log.getUserId(), log.getModule(),
                log.getPermCode(), log.getMethod(), log.getRequestParams(), log.getStatus(),
                log.getErrorMsg(), log.getIp(), log.getTraceId(), log.getCreateTime());
    }
}
