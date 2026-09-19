package com.minimall.domain.sys.repository;

import com.minimall.domain.sys.SysDictData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;

/**
 * 字典数据仓储(架构文档 5.1)。
 *
 * <p>与 {@link SysDictTypeRepository} 同理:平台级表、无过滤器,{@code findById} 用默认实现即可。
 */
public interface SysDictDataRepository extends JpaRepository<SysDictData, Long>,
        QuerydslPredicateExecutor<SysDictData> {

    /**
     * 某字典类型下的全部数据项,按 {@code sort_order} 升序。
     *
     * <p>排序在数据库里做(而不是查回来再排):这个顺序就是前端下拉框的顺序,
     * 排序字段写在 SQL 里能顺带吃到 {@code idx_type} 索引,省一次内存排序。
     */
    List<SysDictData> findByDictTypeOrderBySortOrderAscIdAsc(String dictType);

    boolean existsByDictTypeAndDictValue(String dictType, String dictValue);

    boolean existsByDictTypeAndDictValueAndIdNot(String dictType, String dictValue, Long id);

    long countByDictType(String dictType);

    /**
     * 删除某字典类型下的全部数据项。
     *
     * <p>用于"删除字典类型"时清理子项:字典数据对字典类型是**强从属**(没有类型就没有意义),
     * 与 5.6 里"部门下有用户则拒绝删除"的情形不同 —— 那些子项带着独立的业务语义,
     * 静默裁剪会造成事故;这里清掉的只是配置项本身。
     */
    long deleteByDictType(String dictType);
}
