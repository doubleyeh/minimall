package com.minimall.sys.api.dto;

import java.util.List;

/**
 * 部门树节点(架构文档 5.1:{@code sys_dept.ancestors} 是逗号分隔的祖级链,
 * "本部门及以下"用一条 LIKE 查询,不递归)。
 */
public record DeptTreeNode(
        Long id,
        Long parentId,
        String ancestors,
        String deptName,
        Integer sortOrder,
        Integer status,
        List<DeptTreeNode> children
) {
}
