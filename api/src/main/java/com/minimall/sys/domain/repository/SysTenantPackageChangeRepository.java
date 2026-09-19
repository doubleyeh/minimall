package com.minimall.sys.domain.repository;

import com.minimall.sys.domain.SysTenantPackageChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 套餐变更记录仓储(架构文档 4.8、4.8.1)。
 *
 * <p>纯审计流水:只写不更不删,所以除了继承的方法只需要一个"取最近一次"的查询
 * (要查完整历史时按 tenant_id + create_time 走索引即可,见 9.7)。
 */
public interface SysTenantPackageChangeRepository extends JpaRepository<SysTenantPackageChange, Long> {

    /**
     * 某租户最近一次套餐变更记录。
     *
     * <p>用途:超管排查"这个租户的权限为什么变成这样"时看最近一次变更;用例侧用它断言
     * "记录里说收回了哪些菜单"与"实际收回了哪些"一致(8.1 用例 19)。
     * 按 ID 倒序取即可 —— 主键是雪花 ID,天然按时序递增(4.3)。
     */
    Optional<SysTenantPackageChange> findFirstByTenantIdOrderByIdDesc(Long tenantId);
}
