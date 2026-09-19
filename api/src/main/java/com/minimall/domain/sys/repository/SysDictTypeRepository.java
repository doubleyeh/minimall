package com.minimall.domain.sys.repository;

import com.minimall.domain.sys.SysDictType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Optional;

/**
 * 字典类型仓储(架构文档 5.1)。
 *
 * <p>{@code sys_dict_type} 是**平台级表**(4.6.1):不带 {@code tenant_id}、不参与租户过滤与数据权限,
 * 因此这里的 {@code findById} 用 JpaRepository 的默认实现即可 —— 4.2 里"覆盖 findById"的约束
 * 只针对**挂了 Hibernate Filter 的实体**(那些表按 ID 直查会绕过租户/数据权限条件)。
 * 平台级表没有过滤器,不存在"直查绕过"的问题;访问控制由 {@code system:dict:*} 权限码负责。
 */
public interface SysDictTypeRepository extends JpaRepository<SysDictType, Long>,
        QuerydslPredicateExecutor<SysDictType> {

    Optional<SysDictType> findByDictType(String dictType);

    boolean existsByDictType(String dictType);

    boolean existsByDictTypeAndIdNot(String dictType, Long id);
}
