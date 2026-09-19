package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.QMallGoods;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

/**
 * 商品仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 */
public interface MallGoodsRepository extends JpaRepository<MallGoods, Long>,
        QuerydslPredicateExecutor<MallGoods> {

    @Override
    default Optional<MallGoods> findById(Long id) {
        return findOne(QMallGoods.mallGoods.id.eq(id));
    }

    List<MallGoods> findByGoodsNameContainingOrderByIdDesc(String goodsName);

    /** 分类是否被商品引用 —— 删除分类前的校验(与部门删除前校验同理,不做静默裁剪)。 */
    boolean existsByCategoryId(Long categoryId);

    long countByCategoryId(Long categoryId);

    /** 是否仍有关联该运费模板的商品 —— 删除模板前的校验。 */
    boolean existsByFreightTemplateId(Long freightTemplateId);
}
