package com.minimall.mall.service.impl;

import com.minimall.common.PageResult;
import com.minimall.mall.api.dto.StockWarnReport;
import com.minimall.mall.api.dto.StockWarnView;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.service.StockWarnService;
import com.minimall.sys.service.support.DictIntReader;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存预警(商城设计文档 3.2)。
 */
@Service
@Transactional(readOnly = true)
public class StockWarnServiceImpl implements StockWarnService {

    /** 阈值字典取不到时的兜底值:配置出问题不该让页面整体打不开。 */
    private static final int DEFAULT_THRESHOLD = 5;
    private static final String THRESHOLD_DICT = "stock_warn_threshold";

    private final MallSkuRepository skuRepository;
    private final MallGoodsRepository goodsRepository;
    private final DictIntReader dictIntReader;

    public StockWarnServiceImpl(MallSkuRepository skuRepository, MallGoodsRepository goodsRepository,
                                DictIntReader dictIntReader) {
        this.skuRepository = skuRepository;
        this.goodsRepository = goodsRepository;
        this.dictIntReader = dictIntReader;
    }

    @Override
    public StockWarnReport page(int pageNo, int pageSize) {
        int threshold = dictIntReader.get(THRESHOLD_DICT, DEFAULT_THRESHOLD);
        Page<MallSku> page = skuRepository.findLowStock(threshold,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1)));

        // 商品名与主图批量补齐:逐行去查商品就是 N+1
        Map<Long, MallGoods> goodsById = new HashMap<>();
        goodsRepository.findAllById(page.getContent().stream().map(MallSku::getGoodsId).distinct().toList())
                .forEach(goods -> goodsById.put(goods.getId(), goods));

        List<StockWarnView> views = page.getContent().stream()
                .map(sku -> toView(sku, goodsById.get(sku.getGoodsId())))
                .toList();
        return new StockWarnReport(threshold, PageResult.of(page.getTotalElements(), views));
    }

    private StockWarnView toView(MallSku sku, MallGoods goods) {
        return new StockWarnView(sku.getId(), sku.getGoodsId(),
                goods == null ? null : goods.getGoodsName(),
                imageOf(sku, goods),
                sku.getSkuCode(), sku.getSkuName(),
                sku.getStock(), sku.getLockedStock(), sku.availableStock());
    }

    /** 规格图优先,没有就用商品主图(与订单明细的快照口径一致)。 */
    private String imageOf(MallSku sku, MallGoods goods) {
        if (sku.getSkuImage() != null) {
            return sku.getSkuImage();
        }
        return goods == null ? null : goods.getMainImage();
    }
}
