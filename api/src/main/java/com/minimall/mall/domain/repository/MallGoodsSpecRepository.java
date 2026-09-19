package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallGoodsSpec;
import com.minimall.mall.domain.QMallGoodsSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 商品规格名仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallGoodsSpecRepository extends JpaRepository<MallGoodsSpec, Long>,
        QuerydslPredicateExecutor<MallGoodsSpec> {

    @Override
    default Optional<MallGoodsSpec> findById(Long id) {
        return findOne(QMallGoodsSpec.mallGoodsSpec.id.eq(id));
    }

    List<MallGoodsSpec> findByGoodsIdOrderBySortOrderAscIdAsc(Long goodsId);

    long deleteByGoodsId(Long goodsId);
}
