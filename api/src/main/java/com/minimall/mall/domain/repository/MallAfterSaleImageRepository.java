package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallAfterSaleImage;
import com.minimall.mall.domain.QMallAfterSaleImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 售后凭证图片仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallAfterSaleImageRepository extends JpaRepository<MallAfterSaleImage, Long>,
        QuerydslPredicateExecutor<MallAfterSaleImage> {

    @Override
    default Optional<MallAfterSaleImage> findById(Long id) {
        return findOne(QMallAfterSaleImage.mallAfterSaleImage.id.eq(id));
    }

    List<MallAfterSaleImage> findByAfterSaleIdOrderByIdAsc(Long afterSaleId);
}
