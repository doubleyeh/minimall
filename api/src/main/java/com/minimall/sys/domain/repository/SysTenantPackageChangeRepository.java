package com.minimall.sys.domain.repository;

import com.minimall.sys.domain.SysTenantPackageChange;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 套餐变更记录仓储(架构文档 4.8、4.8.1)。
 *
 * <p>纯审计流水:只追加不更不删。主键是雪花 ID,天然按时序递增(4.3),
 * 所以"哪个更晚写"用 id 比即可,不需要拿 create_time 比。
 */
public interface SysTenantPackageChangeRepository extends JpaRepository<SysTenantPackageChange, Long> {

    /**
     * 某租户最近一次套餐变更记录。
     *
     * <p>用途:超管排查"这个租户的权限为什么变成这样"时看最近一次变更;用例侧用它断言
     * "记录里说收回了哪些菜单"与"实际收回了哪些"一致(8.1 用例 19)。
     */
    Optional<SysTenantPackageChange> findFirstByTenantIdOrderByIdDesc(Long tenantId);

    /**
     * 某租户**当前生效**的套餐变更记录:已生效({@code effectiveDate <= 当天})里 id 最大的那条。
     *
     * <p>注意过滤与排序的先后:必须**先按生效日过滤**再按 id 倒序。写成"按 effectiveDate 排序取第一条"
     * 会让一条未来记录把今天该生效的挡住(见实体类注释里的规则)。
     */
    Optional<SysTenantPackageChange> findFirstByTenantIdAndEffectiveDateLessThanEqualOrderByIdDesc(
            Long tenantId, LocalDate today);

    /** 某租户最近的若干条变更(变更历史抽屉用)。 */
    List<SysTenantPackageChange> findByTenantIdOrderByIdDesc(Long tenantId, Pageable pageable);

    /** 到了生效日、但可能还没应用的租户(去重):任务据此决定要处理哪些租户。 */
    @Query("select distinct c.tenantId from SysTenantPackageChange c where c.effectiveDate <= :today")
    List<Long> findDueTenantIds(@Param("today") LocalDate today);
}
