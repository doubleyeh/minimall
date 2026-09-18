package com.minimall.domain.sys.repository;

import com.minimall.domain.sys.SysMenu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 菜单仓储(架构文档 5.1、5.2.1、5.5)。
 *
 * <p>{@code sys_menu} 是平台级表,不受租户过滤;权限计算时按 ID 批量取菜单即可。
 * 这里的所有查询都显式写 JPQL 而不是派生方法名,理由同 {@code SysRoleRepository}:
 * 字段名以 {@code is} 开头({@code isPlatform}),派生方法名容易踩解析歧义。
 */
public interface SysMenuRepository extends JpaRepository<SysMenu, Long> {

    List<SysMenu> findByIdIn(Collection<Long> ids);

    @Query("select m from SysMenu m where m.status = 1")
    List<SysMenu> findAllEnabled();

    /**
     * 全平台可用(非平台专用)的菜单,用于套餐编辑的候选集(5.2.1 规则 1)。
     */
    @Query("select m from SysMenu m where m.isPlatform = 0")
    List<SysMenu> findAllNonPlatform();

    List<SysMenu> findByParentId(Long parentId);

    /**
     * 按权限标识查菜单。
     *
     * <p>用途:保存菜单前的"友好提示"。**最终一致性靠建表脚本上的唯一索引** ——
     * 应用层先查后插有并发窗口,唯一索引才是可靠的那一道(9.7)。
     */
    java.util.Optional<SysMenu> findByPermCode(String permCode);
}
