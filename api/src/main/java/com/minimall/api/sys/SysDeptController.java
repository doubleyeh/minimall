package com.minimall.api.sys;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.api.sys.dto.DeptSaveRequest;
import com.minimall.api.sys.dto.DeptTreeNode;
import com.minimall.common.ApiResponse;
import com.minimall.service.sys.DeptService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.minimall.infra.audit.AuditLog;

/**
 * 部门管理接口(架构文档 5.1、5.6)。
 */
@RestController
@RequestMapping("/system/depts")
public class SysDeptController {

    private final DeptService deptService;

    public SysDeptController(DeptService deptService) {
        this.deptService = deptService;
    }

    @GetMapping("/tree")
    @SaCheckPermission("system:dept:list")
    public ApiResponse<List<DeptTreeNode>> tree(@RequestParam(required = false) Integer status) {
        return ApiResponse.ok(deptService.tree(status));
    }

    @AuditLog(module = "部门管理", permCode = "system:dept:create")
    @PostMapping
    @SaCheckPermission("system:dept:create")
    public ApiResponse<Long> create(@Valid @RequestBody DeptSaveRequest request) {
        return ApiResponse.ok(deptService.create(request));
    }

    @AuditLog(module = "部门管理", permCode = "system:dept:update")
    @PutMapping("/{deptId}")
    @SaCheckPermission("system:dept:update")
    public ApiResponse<Void> update(@PathVariable Long deptId, @Valid @RequestBody DeptSaveRequest request) {
        deptService.update(deptId, request);
        return ApiResponse.ok();
    }

    @AuditLog(module = "部门管理", permCode = "system:dept:delete")
    @DeleteMapping("/{deptId}")
    @SaCheckPermission("system:dept:delete")
    public ApiResponse<Void> delete(@PathVariable Long deptId) {
        deptService.delete(deptId);
        return ApiResponse.ok();
    }
}
