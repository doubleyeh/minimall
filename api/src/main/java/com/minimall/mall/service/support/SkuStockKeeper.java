package com.minimall.mall.service.support;

import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import org.springframework.stereotype.Component;

/**
 * SKU 库存变更的统一入口:改完库存同步刷新 {@code mall_goods.total_stock}。
 *
 * <p>{@code total_stock} 是冗余汇总,必须在同一事务内与 SKU 库存一起变(见 MallGoods 类注释)。
 * 仓库里那 5 个库存条件更新都包在这里,调用方从本类改库存即可,不会漏刷汇总。
 */
@Component
public class SkuStockKeeper {

    private final MallSkuRepository skuRepository;
    private final MallGoodsRepository goodsRepository;

    public SkuStockKeeper(MallSkuRepository skuRepository, MallGoodsRepository goodsRepository) {
        this.skuRepository = skuRepository;
        this.goodsRepository = goodsRepository;
    }

    /** 下单锁定库存,见 {@link MallSkuRepository#lockStock}。 */
    public int lockStock(Long skuId, Long goodsId, Long tenantId, int quantity) {
        int affected = skuRepository.lockStock(skuId, tenantId, quantity);
        refreshTotalStock(goodsId, tenantId, affected);
        return affected;
    }

    /** 释放锁定库存,见 {@link MallSkuRepository#releaseLockedStock}。 */
    public int releaseLockedStock(Long skuId, Long goodsId, Long tenantId, int quantity) {
        int affected = skuRepository.releaseLockedStock(skuId, tenantId, quantity);
        refreshTotalStock(goodsId, tenantId, affected);
        return affected;
    }

    /** 支付成功扣减实际库存并解除锁定,见 {@link MallSkuRepository#deductStockOnPaid}。 */
    public int deductStockOnPaid(Long skuId, Long goodsId, Long tenantId, int quantity) {
        int affected = skuRepository.deductStockOnPaid(skuId, tenantId, quantity);
        refreshTotalStock(goodsId, tenantId, affected);
        return affected;
    }

    /** 只扣实际库存、不动锁定,见 {@link MallSkuRepository#deductStockOnly}。 */
    public int deductStockOnly(Long skuId, Long goodsId, Long tenantId, int quantity) {
        int affected = skuRepository.deductStockOnly(skuId, tenantId, quantity);
        refreshTotalStock(goodsId, tenantId, affected);
        return affected;
    }

    /** 回库,见 {@link MallSkuRepository#restoreStock}。 */
    public int restoreStock(Long skuId, Long goodsId, Long tenantId, int quantity) {
        int affected = skuRepository.restoreStock(skuId, tenantId, quantity);
        refreshTotalStock(goodsId, tenantId, affected);
        return affected;
    }

    /** 库存没变(受影响行数为 0)时不刷新:避免白跑一次求和与一次清空持久化上下文。 */
    private void refreshTotalStock(Long goodsId, Long tenantId, int affected) {
        if (affected == 0 || goodsId == null) {
            return;
        }
        goodsRepository.updateTotalStock(goodsId, tenantId, (int) skuRepository.sumStockByGoodsId(goodsId, tenantId));
    }
}
