package com.minimall.service.sys;

import com.minimall.api.sys.dto.TenantCreateRequest;
import com.minimall.api.sys.dto.TenantCreateResponse;
import com.minimall.api.sys.dto.TenantPackageChangeRequest;
import com.minimall.api.sys.dto.TenantView;
import com.minimall.common.PageResult;

/**
 * 租户管理(平台级能力,只有平台超管可用,见架构文档 4.10)。
 *
 * <p>租户不做物理删除,只有禁用(架构文档 4.11/5.6)。
 */
public interface TenantService {

    /**
     * 创建租户。严格按 4.7 的六步在一个事务里完成:
     * 租户 -> 默认管理员角色(is_default=1, data_scope=5) -> 套餐菜单写入 sys_role_menu -> 根部门 -> 管理员用户(is_super=0)。
     * 任一步失败整体回滚。
     *
     * @return 含租户ID、管理员账号与"仅在本次返回"的初始密码
     */
    TenantCreateResponse create(TenantCreateRequest request);

    /**
     * 租户分页列表。{@code tenant} 是平台级表(4.6.1),查询本身不经过租户过滤。
     */
    PageResult<TenantView> page(String tenantCode, Integer status, int pageNo, int pageSize);

    /**
     * 变更租户套餐(升级/降级)。差异计算与生效规则见 4.8:
     * 新增只同步默认管理员角色,收回对该租户全部角色立即生效;同时写 sys_tenant_package_change 审计记录。
     */
    void changePackage(Long tenantId, TenantPackageChangeRequest request);

    /**
     * 启用/禁用租户。禁用时先删 Redis 的租户状态缓存再落库,使该租户在线会话在下一次请求即被拦下(4.11)。
     */
    void changeStatus(Long tenantId, int status);
}
