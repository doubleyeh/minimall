package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallMemberLevel;
import com.minimall.mall.domain.QMallMemberLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 会员等级仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallMemberLevelRepository extends JpaRepository<MallMemberLevel, Long>,
        QuerydslPredicateExecutor<MallMemberLevel> {

    @Override
    default Optional<MallMemberLevel> findById(Long id) {
        return findOne(QMallMemberLevel.mallMemberLevel.id.eq(id));
    }

    List<MallMemberLevel> findByOrderByLevelSortAsc();

    boolean existsByLevelName(String levelName);

    boolean existsByLevelNameAndIdNot(String levelName, Long id);
}
