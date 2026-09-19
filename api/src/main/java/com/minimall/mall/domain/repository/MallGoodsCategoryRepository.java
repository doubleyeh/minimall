package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallGoodsCategory;
import com.minimall.mall.domain.QMallGoodsCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 商品分类仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallGoodsCategoryRepository extends JpaRepository<MallGoodsCategory, Long>,
        QuerydslPredicateExecutor<MallGoodsCategory> {

    @Override
    default Optional<MallGoodsCategory> findById(Long id) {
        return findOne(QMallGoodsCategory.mallGoodsCategory.id.eq(id));
    }

    List<MallGoodsCategory> findByOrderBySortOrderAscIdAsc();

    List<MallGoodsCategory> findByParentIdOrderBySortOrderAscIdAsc(Long parentId);

    boolean existsByParentId(Long parentId);

    /** 同租户同层级下分类名不重复(租户维度由过滤器保证,不需要在方法名里再带 tenantId)。 */
    boolean existsByParentIdAndCategoryName(Long parentId, String categoryName);

    boolean existsByParentIdAndCategoryNameAndIdNot(Long parentId, String categoryName, Long id);
}
