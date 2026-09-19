package com.minimall.sys.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.sys.api.dto.UserCreateResponse;
import com.minimall.sys.api.dto.UserResetPasswordResponse;
import com.minimall.sys.api.dto.UserSaveRequest;
import com.minimall.sys.api.dto.UserView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.sys.service.UserService;
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
import com.minimall.infra.audit.AuditLog;

/**
 * 用户管理接口(架构文档 5.3、5.6、7.1.2)。
 *
 * <p>这一组接口同时受两层约束,注意不要混为一谈:
 * <ul>
 *   <li>菜单/按钮权限({@code @SaCheckPermission}):决定"能不能调这个接口"</li>
 *   <li>数据权限(Hibernate Filter,按 5.3 的 data_scope):决定"这个接口返回哪些部门的数据"</li>
 * </ul>
 */
@RestController
@RequestMapping("/system/users")
public class SysUserController {

    private final UserService userService;

    public SysUserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @SaCheckPermission("system:user:list")
    public ApiResponse<PageResult<UserView>> page(@RequestParam(required = false) String username,
                                                 @RequestParam(required = false) Long deptId,
                                                 @RequestParam(required = false) Integer status,
                                                 @RequestParam(defaultValue = "1") int pageNo,
                                                 @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(userService.page(username, deptId, status, pageNo, pageSize));
    }

    /**
     * 详情。不在可见范围内时返回统一的"资源不存在"(7.3),不区分"存在但无权"和"不存在"。
     */
    @GetMapping("/{userId}")
    @SaCheckPermission("system:user:query")
    public ApiResponse<UserView> detail(@PathVariable Long userId) {
        return ApiResponse.ok(userService.detail(userId));
    }

    @AuditLog(module = "用户管理", permCode = "system:user:create")
    @PostMapping
    @SaCheckPermission("system:user:create")
    public ApiResponse<UserCreateResponse> create(@Valid @RequestBody UserSaveRequest request) {
        return ApiResponse.ok(userService.create(request));
    }

    @AuditLog(module = "用户管理", permCode = "system:user:update")
    @PutMapping("/{userId}")
    @SaCheckPermission("system:user:update")
    public ApiResponse<Void> update(@PathVariable Long userId, @Valid @RequestBody UserSaveRequest request) {
        userService.update(userId, request);
        return ApiResponse.ok();
    }

    @AuditLog(module = "用户管理", permCode = "system:user:delete")
    @DeleteMapping("/{userId}")
    @SaCheckPermission("system:user:delete")
    public ApiResponse<Void> delete(@PathVariable Long userId) {
        userService.delete(userId);
        return ApiResponse.ok();
    }

    @AuditLog(module = "用户管理", permCode = "system:user:status")
    @PutMapping("/{userId}/status")
    @SaCheckPermission("system:user:status")
    public ApiResponse<Void> changeStatus(@PathVariable Long userId, @RequestParam int status) {
        userService.changeStatus(userId, status);
        return ApiResponse.ok();
    }

    /**
     * 重置密码:明文只在本次响应返回一次,并强制该用户下次登录改密(7.1.2)。
     */
    @AuditLog(module = "用户管理", permCode = "system:user:reset-password")
    @PostMapping("/{userId}/password/reset")
    @SaCheckPermission("system:user:reset-password")
    public ApiResponse<UserResetPasswordResponse> resetPassword(@PathVariable Long userId) {
        return ApiResponse.ok(userService.resetPassword(userId));
    }

    /**
     * 解除登录锁定:清空 lock_time 与 login_fail_count(7.1.1 的锁定只能等 15 分钟自然过期,
     * 这是人工放行的出口)。
     */
    @AuditLog(module = "用户管理", permCode = "system:user:unlock")
    @PostMapping("/{userId}/unlock")
    @SaCheckPermission("system:user:unlock")
    public ApiResponse<Void> unlock(@PathVariable Long userId) {
        userService.unlock(userId);
        return ApiResponse.ok();
    }
}
