package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallSkuSpecValue;
import com.minimall.mall.domain.QMallSkuSpecValue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * SKU-规格值关联仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallSkuSpecValueRepository extends JpaRepository<MallSkuSpecValue, Long>,
        QuerydslPredicateExecutor<MallSkuSpecValue> {

    @Override
    default Optional<MallSkuSpecValue> findById(Long id) {
        return findOne(QMallSkuSpecValue.mallSkuSpecValue.id.eq(id));
    }

    List<MallSkuSpecValue> findBySkuIdIn(Collection<Long> skuIds);

    List<MallSkuSpecValue> findBySkuId(Long skuId);

    long deleteBySkuId(Long skuId);

    long deleteBySpecValueId(Long specValueId);
}
