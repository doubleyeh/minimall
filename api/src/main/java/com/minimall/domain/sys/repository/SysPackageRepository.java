package com.minimall.domain.sys.repository;

import com.minimall.domain.sys.SysPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 套餐仓储(架构文档 4.7、4.8.1、5.6)。
 */
public interface SysPackageRepository extends JpaRepository<SysPackage, Long>,
        org.springframework.data.querydsl.QuerydslPredicateExecutor<SysPackage> {

    boolean existsByPackageName(String packageName);

    /**
     * 找出包含某个菜单的套餐(删除菜单时要做级联清理,5.6)。
     */
    @Query("select p.id from SysPackage p where :menuId member of p.menuIds")
    List<Long> findPackageIdsByMenuId(@Param("menuId") Long menuId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "delete from sys_package_menu where menu_id = :menuId", nativeQuery = true)
    int deletePackageMenusByMenuId(@Param("menuId") Long menuId);
}
