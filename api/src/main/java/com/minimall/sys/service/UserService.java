package com.minimall.sys.service;

import com.minimall.sys.api.dto.UserCreateResponse;
import com.minimall.sys.api.dto.UserResetPasswordResponse;
import com.minimall.sys.api.dto.UserSaveRequest;
import com.minimall.sys.api.dto.UserView;
import com.minimall.common.PageResult;

/**
 * 用户管理(架构文档 5.3、5.6、7.1.2)。
 *
 * <p><b>数据权限</b>:{@code sys_user} 是参与数据权限过滤的表之一(4.6.1),
 * 列表与详情都会自动按当前用户角色的 {@code data_scope} 收窄范围,服务层不需要手写部门条件。
 *
 * <p><b>越权防护</b>:所有按 ID 的读/改/删必须先经过滤查询加载实体(7.3),
 * 加载不到一律当作"资源不存在",不要用 bulk update 直接改。
 *
 * <p><b>租户隔离</b>:{@code tenant_id} 由基类监听器自动回填(4.5),服务层不要手动赋值。
 */
public interface UserService {

    /**
     * 分页查询。{@code deptId} 是"业务筛选条件",与数据权限的部门范围是两个东西:
     * 前者是用户主动选的范围,后者是系统强加的上限,两者是"与"的关系。
     */
    PageResult<UserView> page(String username, Long deptId, Integer status, int pageNo, int pageSize);

    /** 详情。加载不到(不存在或不在可见范围内)返回 NOT_FOUND。 */
    UserView detail(Long userId);

    /** 新增用户。不传密码则随机生成并在本次响应里回传一次明文。 */
    UserCreateResponse create(UserSaveRequest request);

    /** 修改用户。username/password 被忽略(见 UserSaveRequest 的说明)。 */
    void update(Long userId, UserSaveRequest request);

    /**
     * 删除用户(校验见 5.6):不能删自己;不能删该租户最后一个默认管理员角色的持有者;
     * 级联清 sys_user_role;历史操作日志保留。
     */
    void delete(Long userId);

    /** 禁用用户:在线会话按 4.11 的方式在下一个请求失效。 */
    void changeStatus(Long userId, int status);

    /** 超管重置密码,返回一次性明文。 */
    UserResetPasswordResponse resetPassword(Long userId);

    /** 手动解除登录锁定(清空 lock_time 与 login_fail_count),用于用户被锁后人工放行。 */
    void unlock(Long userId);
}
