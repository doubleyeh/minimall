package com.minimall.sys.service;

import com.minimall.sys.api.dto.DeptSaveRequest;
import com.minimall.sys.api.dto.DeptTreeNode;

import java.util.List;

/**
 * 部门管理(架构文档 4.6.1、5.1、5.6)。
 *
 * <p>部门**不参与数据权限过滤**(4.6.1:部门树是组织架构而不是业务数据,租户内全员可见),
 * 能不能改部门由菜单权限控制。数据权限作用在"带 dept_id 的业务表"上,部门树只是那条规则的基础数据。
 */
public interface DeptService {

    /**
     * 部门树。{@code status} 为空返回全部;前端选择部门时通常只传启用状态。
     */
    List<DeptTreeNode> tree(Integer status);

    /**
     * 新增部门。服务端负责维护 ancestors = 父部门.ancestors + "," + parentId(父为 0 时为空串)。
     */
    Long create(DeptSaveRequest request);

    /**
     * 修改部门。校验:不能把部门挂到自己的子孙节点下(会成环,ancestors 语义随之崩掉);
     * 变更父级时要级联更新子孙的 ancestors。
     */
    void update(Long deptId, DeptSaveRequest request);

    /**
     * 删除部门(5.6):存在子部门或关联用户时拒绝删除,不做级联裁剪。
     */
    void delete(Long deptId);
}
