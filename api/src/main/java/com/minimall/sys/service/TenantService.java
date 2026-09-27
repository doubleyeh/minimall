package com.minimall.sys.service;

import com.minimall.sys.api.dto.TenantCreateRequest;
import com.minimall.sys.api.dto.TenantCreateResponse;
import com.minimall.sys.api.dto.TenantPackageChangeRequest;
import com.minimall.sys.api.dto.TenantView;
import com.minimall.common.PageResult;
import com.minimall.infra.tenant.TenantSnapshot;

import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 修改租户有效期,{@code expireTime} 为空表示改为不过期。
     *
     * <p>与启停一样是惰性生效:删掉租户状态缓存后,该租户用户的下一个请求就会按新有效期校验(4.11)。
     * 改成已过去的时间等价于立刻禁用,会一并撤销全部刷新令牌。
     */
    void changeExpireTime(Long tenantId, LocalDateTime expireTime);

    /**
     * 列出在 {@code (from, to]} 区间内到期的启用中租户,供到期提醒用。
     */
    List<TenantSnapshot> listExpiringBetween(LocalDateTime from, LocalDateTime to);

    /**
     * 注销租户:必须先禁用,再走这一步。记录数据清理时间(默认 90 天后),期间数据一行不动。
     *
     * <p>两步走是刻意的:注销不可逆(到期后数据被物理删除),一步到位太容易误点。
     * 禁用那一步已经会踢下线并撤销刷新令牌,租户立刻不可用。
     */
    void close(Long tenantId);

    /** 取消注销:保留期内都有效。数据本来就还在,清掉清理时间即可(租户仍是禁用状态)。 */
    void cancelClose(Long tenantId);

    /**
     * 物理删除已过保留期的注销租户及其全部数据,返回清理的租户数。
     *
     * <p>仅供定时任务调用。不可逆,删除前唯一的退路是导出存档(见 TenantDataExporter)。
     */
    int purgeExpiredTenants();

    /**
     * 把已过有效期的启用中租户置为禁用,返回处理条数。
     *
     * <p>仅供定时任务调用:有效期此前只是"用户请求时被动校验",没人主动处理,
     * 表现是租户过期后库里状态还一直是"正常"。幂等(只挑 status=1 的),任务重跑安全。
     */
    int disableExpiredTenants();
}
