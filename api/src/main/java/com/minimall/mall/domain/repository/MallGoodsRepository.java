package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.QMallGoods;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

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

    /**
     * 刷新冗余汇总 {@code total_stock}(见 {@link MallGoods} 类注释:必须与 SKU 库存变更同事务)。
     *
     * <p>与 SKU 库存一样用条件 UPDATE:调用方不该为了改一个汇总列先把商品实体查出来。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallGoods g set g.totalStock = :totalStock "
            + "where g.id = :goodsId and g.tenantId = :tenantId")
    int updateTotalStock(@Param("goodsId") Long goodsId, @Param("tenantId") Long tenantId,
                         @Param("totalStock") int totalStock);
}
