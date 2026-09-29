package com.minimall.mall.domain.repository;

import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.QMallSku;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * SKU 仓储。{@code findById} 必须走查询,原因见本包 {@code package-info}。
 *
 * <p><b>下面几个库存变动的 {@code @Modifying} 方法是并发安全的唯一落点,不要改成"先查再改"</b>:
 * 库存是典型的"读-改-写"竞态场景,两个用户同时买最后一件时,"先查可售库存 → 判断 → setStock"
 * 会让两条请求都通过判断然后各扣一次,直接超卖。这里的每条 SQL 都把"判断"写进了 {@code WHERE}:
 * <b>受影响行数为 0 即表示条件不满足</b>(库存不足/状态不对),调用方据此回滚,不需要额外加锁。
 *
 * <p>注意 JPQL 批量更新**不经过 Hibernate 过滤器**(过滤器只作用于查询),
 * 所以这里的每条语句都显式带上 {@code tenantId} 条件 —— 少写这一条就是跨租户改库存。
 */
public interface MallSkuRepository extends JpaRepository<MallSku, Long>,
        QuerydslPredicateExecutor<MallSku> {

    @Override
    default Optional<MallSku> findById(Long id) {
        return findOne(QMallSku.mallSku.id.eq(id));
    }

    List<MallSku> findByGoodsIdOrderByIdAsc(Long goodsId);

    Optional<MallSku> findByTenantIdAndSkuCode(Long tenantId, String skuCode);

    long countByGoodsId(Long goodsId);

    long deleteByGoodsId(Long goodsId);

    /**
     * 下单锁定库存(3.3 第 5 步):{@code lockedStock += quantity}。
     *
     * <p>条件里的 {@code stock - lockedStock >= quantity} 就是"可售库存足够"。
     *
     * @return 受影响行数;0 表示可售库存不足或 SKU 已停售,调用方必须回滚整单
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSku s set s.lockedStock = s.lockedStock + :quantity "
            + "where s.id = :skuId and s.tenantId = :tenantId and s.status = 1 "
            + "and (s.stock - s.lockedStock) >= :quantity")
    int lockStock(@Param("skuId") Long skuId, @Param("tenantId") Long tenantId, @Param("quantity") int quantity);

    /**
     * 释放锁定库存(订单超时/买家取消,3.4):{@code lockedStock -= quantity}。
     *
     * <p>条件 {@code lockedStock >= quantity} 是幂等保护:重复释放不会把锁定值扣成负数。
     *
     * @return 受影响行数;0 表示无可释放的锁定(可能已被处理过),调用方按"已释放"处理即可
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSku s set s.lockedStock = s.lockedStock - :quantity "
            + "where s.id = :skuId and s.tenantId = :tenantId and s.lockedStock >= :quantity")
    int releaseLockedStock(@Param("skuId") Long skuId, @Param("tenantId") Long tenantId,
                           @Param("quantity") int quantity);

    /**
     * 支付成功扣减实际库存并解除锁定(3.8):{@code stock -= quantity} 且 {@code lockedStock -= quantity}。
     *
     * <p>两个字段必须同时减:只减 locked 会让货永远挂着卖不出去,只减 stock 会让可售库存虚高。
     *
     * @return 受影响行数;0 表示回调与当前库存状态不符(可能是重复回调),调用方需按幂等分支处理
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSku s set s.stock = s.stock - :quantity, s.lockedStock = s.lockedStock - :quantity "
            + "where s.id = :skuId and s.tenantId = :tenantId and s.lockedStock >= :quantity")
    int deductStockOnPaid(@Param("skuId") Long skuId, @Param("tenantId") Long tenantId,
                          @Param("quantity") int quantity);

    /**
     * 只扣实际库存,不动锁定(3.8):把"已关闭但钱已收"的订单置回待发货时用。
     *
     * <p>不能复用 {@link #deductStockOnPaid}:关单时已经 {@code releaseLockedStock} 把锁定退回去了,
     * 这里再减一次 lockedStock 会把**别的订单**的锁定扣掉。条件 {@code stock >= quantity} 防负库存。
     *
     * @return 受影响行数;0 表示库存不足,调用方需要提示而不是硬发
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSku s set s.stock = s.stock - :quantity "
            + "where s.id = :skuId and s.tenantId = :tenantId and s.stock >= :quantity")
    int deductStockOnly(@Param("skuId") Long skuId, @Param("tenantId") Long tenantId,
                        @Param("quantity") int quantity);

    /** 退款/退货回库(3.9 终态动作):{@code stock += quantity}。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MallSku s set s.stock = s.stock + :quantity "
            + "where s.id = :skuId and s.tenantId = :tenantId")
    int restoreStock(@Param("skuId") Long skuId, @Param("tenantId") Long tenantId,
                     @Param("quantity") int quantity);

    /**
     * 某商品启用 SKU 的实际库存之和 —— {@code mall_goods.total_stock} 的汇总来源。
     *
     * <p>口径必须与商品保存时的重算一致(只算 {@code status = 1} 的 SKU,见
     * {@code GoodsServiceImpl#recalcSummary}),否则两条路径算出的汇总值会互相打架。
     */
    @Query("select coalesce(sum(s.stock), 0) from MallSku s "
            + "where s.goodsId = :goodsId and s.tenantId = :tenantId and s.status = 1")
    long sumStockByGoodsId(@Param("goodsId") Long goodsId, @Param("tenantId") Long tenantId);

    /**
     * 低库存规格:{@code stock - lockedStock <= threshold}。
     *
     * <p>判据是**可售库存**而不是 {@code stock}:只看 stock 会把"挂着一堆待付款订单"的规格漏掉,
     * 那正是最该处理的。带上商品一起查并限定**上架**({@code g.status = 1})与**在售 SKU**:
     * 下架商品的低库存不是问题,列出来只会让人觉得预警不准。
     *
     * <p>按可售库存升序 —— 最该处理的排在最前面。
     */
    @Query("select s from MallSku s, MallGoods g "
            + "where g.id = s.goodsId and g.status = 1 and s.status = 1 "
            + "and (s.stock - s.lockedStock) <= :threshold "
            + "order by (s.stock - s.lockedStock) asc, s.id asc")
    Page<MallSku> findLowStock(@Param("threshold") int threshold, Pageable pageable);
}
