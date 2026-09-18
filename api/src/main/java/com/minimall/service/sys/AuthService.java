package com.minimall.service.sys;

import com.minimall.api.sys.dto.ChangePasswordRequest;
import com.minimall.api.sys.dto.LoginRequest;
import com.minimall.api.sys.dto.LoginResponse;
import com.minimall.api.sys.dto.PermissionSnapshot;
import com.minimall.api.sys.dto.RefreshTokenRequest;
import com.minimall.api.sys.dto.RefreshTokenResponse;

/**
 * 认证与登录态相关的业务接口(实现见 infra/impl 侧,契约见架构文档 7.1.1、7.1.2、5.5)。
 *
 * <p>说明:service 允许引用 api 层的 DTO(接口契约对象),但**不允许引用 Controller**;
 * 依赖方向由 ArchitectureTest 守门。
 *
 * <p>登录时的租户识别不走通用过滤链路(此时还没有 tenantId 可用),必须在实现里单独处理:
 * 先按 tenantCode 定位租户(命中 4.11 的租户状态缓存),再按 (tenant_id, username) 查用户。
 */
public interface AuthService {

    /**
     * 登录。处理顺序严格按架构文档 7.1.1 的 6 步:
     * 定位租户 -> 定位用户 -> 检查 lock_time -> BCrypt 比对(失败累加/锁定) -> StpUtil.login + 写会话扩展 -> 强制改密标记。
     */
    LoginResponse login(LoginRequest request);

    /**
     * 用刷新令牌换一对新令牌(架构文档 7.1.3)。
     *
     * <p>实现必须依次做这几件事,顺序不能变:
     * <ol>
     *   <li>取刷新令牌载荷;取不到就看是不是"已用令牌"——是则判定重放:
     *       **撤销该用户全部刷新令牌 + 踢掉全部会话**,然后按 401 失败</li>
     *   <li>校验租户状态(4.11)与用户状态:任一不可用 → 撤销该用户全部刷新令牌后 401</li>
     *   <li>{@code TenantContext.runAsTenant(tenantId, isSuperUser, ...)} 进入上下文
     *       —— 本接口在白名单里、没有登录态,租户只能从载荷来(不能用超管模式)</li>
     *   <li>{@code StpUtil.login(userId)} 签发新访问令牌并写入会话扩展数据(同 7.1.1 第 5 步)</li>
     *   <li>轮换刷新令牌并返回新令牌对</li>
     * </ol>
     *
     * <p>失败一律 401 + 统一文案,不区分"过期/不存在/已撤销"(避免成为探测接口)。
     */
    RefreshTokenResponse refresh(RefreshTokenRequest request);

    /**
     * 登出当前会话,并撤销本次登录的刷新令牌(7.1.3)。
     */
    void logout();

    /**
     * 修改当前用户密码。校验旧密码、复杂度、新旧不同;成功后清空 login_fail_count/lock_time
     * 并强制该用户全部会话失效。
     */
    void changePassword(ChangePasswordRequest request);

    /**
     * 取当前登录用户的权限快照(menus + permCodes),用于前端刷新动态路由与按钮。
     */
    PermissionSnapshot currentPermissions();
}
