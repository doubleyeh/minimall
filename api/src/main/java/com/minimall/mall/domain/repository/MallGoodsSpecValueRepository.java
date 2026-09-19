package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallGoodsSpecValue;
import com.minimall.mall.domain.QMallGoodsSpecValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 规格值仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallGoodsSpecValueRepository extends JpaRepository<MallGoodsSpecValue, Long>,
        QuerydslPredicateExecutor<MallGoodsSpecValue> {

    @Override
    default Optional<MallGoodsSpecValue> findById(Long id) {
        return findOne(QMallGoodsSpecValue.mallGoodsSpecValue.id.eq(id));
    }

    List<MallGoodsSpecValue> findBySpecIdOrderBySortOrderAscIdAsc(Long specId);

    List<MallGoodsSpecValue> findBySpecIdInOrderBySortOrderAscIdAsc(Collection<Long> specIds);

    long deleteBySpecId(Long specId);
}
