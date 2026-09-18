package com.minimall.service.sys.impl;

import com.minimall.api.sys.dto.DeptSaveRequest;
import com.minimall.api.sys.dto.DeptTreeNode;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.domain.sys.SysDept;
import com.minimall.domain.sys.repository.SysDeptRepository;
import com.minimall.domain.sys.repository.SysUserRepository;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.service.sys.DeptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 部门管理实现(架构文档 5.1、5.3、5.6)。
 *
 * <p><b>{@code ancestors} 只由服务端维护</b>:它是"本部门及以下"数据权限的计算基础,
 * 客户端能改它等于能伪造层级关系,从而绕过部门范围限制(5.3)。
 */
@Service
@Transactional
public class DeptServiceImpl implements DeptService {

    private static final Logger log = LoggerFactory.getLogger(DeptServiceImpl.class);

    private final SysDeptRepository deptRepository;
    private final SysUserRepository userRepository;

    public DeptServiceImpl(SysDeptRepository deptRepository, SysUserRepository userRepository) {
        this.deptRepository = deptRepository;
        this.userRepository = userRepository;
    }

    @Override
    public List<DeptTreeNode> tree(Integer status) {
        List<SysDept> depts = loadTenantDepts().stream()
                .filter(dept -> status == null || status.equals(dept.getStatus()))
                .toList();
        return buildTree(depts);
    }

    @Override
    public Long create(DeptSaveRequest request) {
        Long tenantId = requireTenantId();
        if (deptRepository.existsByTenantIdAndDeptName(tenantId, request.deptName())) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "部门名称在同租户内不能重复");
        }
        SysDept parent = resolveParent(request.parentId());

        SysDept dept = new SysDept();
        dept.setParentId(parent == null ? 0L : parent.getId());
        dept.setAncestors(parent == null ? "" : childAncestors(parent));
        dept.setDeptName(request.deptName());
        dept.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        dept.setStatus(request.status() == null ? 1 : request.status());
        return deptRepository.save(dept).getId();
    }

    @Override
    public void update(Long deptId, DeptSaveRequest request) {
        Long tenantId = requireTenantId();
        SysDept dept = load(deptId);
        SysDept newParent = resolveParent(request.parentId());

        if (!dept.getDeptName().equals(request.deptName())
                && deptRepository.existsByTenantIdAndDeptName(tenantId, request.deptName())) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "部门名称在同租户内不能重复");
        }
        if (newParent != null) {
            if (newParent.getId().equals(dept.getId())) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "上级部门不能是自己");
            }
            if (isDescendantOf(newParent, dept)) {
                // 挂到自己的子孙下面会让部门树成环,ancestors 语义随之崩掉(5.3 的数据权限会算错)
                throw new BusinessException(ErrorCode.PARAM_INVALID, "上级部门不能是自己的下级");
            }
        }

        String newAncestors = newParent == null ? "" : childAncestors(newParent);
        if (!newAncestors.equals(dept.getAncestors())) {
            cascadeAncestors(dept, newAncestors);
        }
        dept.setParentId(newParent == null ? 0L : newParent.getId());
        dept.setAncestors(newAncestors);
        dept.setDeptName(request.deptName());
        if (request.sortOrder() != null) {
            dept.setSortOrder(request.sortOrder());
        }
        if (request.status() != null) {
            dept.setStatus(request.status());
        }
    }

    @Override
    public void delete(Long deptId) {
        SysDept dept = load(deptId);
        if (deptRepository.existsByParentId(deptId)) {
            // 不级联删除:部门树被静默裁剪是数据事故(5.6)
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "存在下级部门,请先处理下级部门");
        }
        long users = userRepository.countByDeptId(deptId);
        if (users > 0) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "该部门下仍有 " + users + " 个用户,请先调整用户归属");
        }
        deptRepository.delete(dept);
    }

    /**
     * 移动部门时,子孙的 {@code ancestors} 前缀要一起替换,否则"本部门及以下"会算漏(5.3)。
     */
    private void cascadeAncestors(SysDept dept, String newAncestors) {
        String oldPrefix = SysDept.pathPrefix(dept.getAncestors(), dept.getId());
        String newPrefix = SysDept.pathPrefix(newAncestors, dept.getId());
        List<SysDept> descendants = deptRepository.findByAncestorsStartingWith(oldPrefix);
        for (SysDept descendant : descendants) {
            descendant.setAncestors(newPrefix + descendant.getAncestors().substring(oldPrefix.length()));
        }
        if (!descendants.isEmpty()) {
            log.info("部门移动,已级联更新子孙祖级链 deptId={} 子孙数={}", dept.getId(), descendants.size());
        }
    }

    /** 子部门的 ancestors = 父部门的 ancestors + 父部门 id(逗号分隔)。根部门为空串。 */
    private String childAncestors(SysDept parent) {
        String parentAncestors = parent.getAncestors() == null ? "" : parent.getAncestors();
        return parentAncestors.isEmpty() ? String.valueOf(parent.getId()) : parentAncestors + "," + parent.getId();
    }

    private boolean isDescendantOf(SysDept candidate, SysDept ancestor) {
        String ancestors = candidate.getAncestors();
        if (ancestors == null || ancestors.isEmpty()) {
            return false;
        }
        return Arrays.stream(ancestors.split(","))
                .anyMatch(part -> part.equals(String.valueOf(ancestor.getId())));
    }

    private SysDept resolveParent(Long parentId) {
        if (parentId == null || parentId == 0L) {
            return null;
        }
        // findById 受租户过滤保护:别租户的部门在这里查不到,天然挡掉跨租户挂载(4.6.1)
        return deptRepository.findById(parentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "上级部门不存在"));
    }

    private SysDept load(Long deptId) {
        return deptRepository.findById(deptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private List<SysDept> loadTenantDepts() {
        return deptRepository.findByTenantIdOrderBySortOrderAsc(requireTenantId());
    }

    private List<DeptTreeNode> buildTree(List<SysDept> depts) {
        Map<Long, List<SysDept>> childrenByParent = new LinkedHashMap<>();
        for (SysDept dept : depts) {
            childrenByParent.computeIfAbsent(dept.getParentId(), key -> new ArrayList<>()).add(dept);
        }
        return buildChildren(0L, childrenByParent);
    }

    private List<DeptTreeNode> buildChildren(Long parentId, Map<Long, List<SysDept>> childrenByParent) {
        List<SysDept> children = childrenByParent.getOrDefault(parentId, List.of());
        return children.stream()
                .sorted(Comparator.comparing((SysDept dept) -> dept.getSortOrder() == null ? 0 : dept.getSortOrder())
                        .thenComparing(SysDept::getId))
                .map(dept -> new DeptTreeNode(dept.getId(), dept.getParentId(), dept.getAncestors(), dept.getDeptName(),
                        dept.getSortOrder(), dept.getStatus(), buildChildren(dept.getId(), childrenByParent)))
                .toList();
    }

    private Long requireTenantId() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return tenantId;
    }
}
