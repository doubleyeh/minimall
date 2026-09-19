package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallGoodsImage;
import com.minimall.mall.domain.QMallGoodsImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 商品轮播图仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallGoodsImageRepository extends JpaRepository<MallGoodsImage, Long>,
        QuerydslPredicateExecutor<MallGoodsImage> {

    @Override
    default Optional<MallGoodsImage> findById(Long id) {
        return findOne(QMallGoodsImage.mallGoodsImage.id.eq(id));
    }

    List<MallGoodsImage> findByGoodsIdOrderBySortOrderAscIdAsc(Long goodsId);

    long deleteByGoodsId(Long goodsId);
}
