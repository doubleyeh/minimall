package com.minimall.mall.service;

import com.minimall.mall.api.dto.StockWarnView;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallSku;
import com.minimall.sys.api.dto.DictDataSaveRequest;
import com.minimall.sys.api.dto.DictDataView;
import com.minimall.sys.service.DictService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存预警(商城设计文档 3.2)。
 *
 * <p>这里最要紧的一条是**判据是可售库存而不是实际库存**:只看 {@code stock} 会把
 * "挂着十几件待付款订单"的规格漏掉 —— 那恰恰是最该处理的。这类漏报不会有任何报错,
 * 只表现为"预警页面看着挺正常,但该缺的货还是缺了"。
 */
class StockWarnServiceIntegrationTest extends MallClientServiceTestBase {

    private static final String THRESHOLD_DICT = "stock_warn_threshold";

    @Autowired
    private StockWarnService stockWarnService;
    @Autowired
    private DictService dictService;

    /** 本类额外造的 SKU(排序用例需要):基类只清它自己那个 skuId。 */
    private final List<Long> extraSkuIds = new ArrayList<>();
    private Long thresholdDataId;
    private String originalThreshold;

    @BeforeEach
    void rememberThreshold() {
        DictDataView data = inTenant(() -> dictService.listData(THRESHOLD_DICT).get(0));
        thresholdDataId = data.id();
        originalThreshold = data.dictValue();
    }

    @AfterEach
    void restoreThresholdAndExtraSkus() {
        // 字典是平台级数据、跨用例共用:改过必须还原,否则后面用例的阈值跟着变
        inTenant(() -> {
            updateThreshold(originalThreshold);
            for (Long id : extraSkuIds) {
                skuRepository.findById(id).ifPresent(skuRepository::delete);
            }
            extraSkuIds.clear();
            return null;
        });
    }

    @Test
    @DisplayName("判据是可售库存:实际库存 10 件但锁了 7 件,一样要报出来")
    void warnsOnAvailableStock() {
        setStock(10, 7);

        StockWarnView mine = findMine();

        assertThat(mine).as("可售只剩 3 件;按实际库存 10 件判断就会漏报").isNotNull();
        assertThat(mine.stock()).isEqualTo(10);
        assertThat(mine.lockedStock()).isEqualTo(7);
        assertThat(mine.availableStock()).isEqualTo(3);
        assertThat(mine.goodsName()).as("页面要显示商品名,不能只有 ID").isEqualTo("集成测试商品");
    }

    @Test
    @DisplayName("阈值是闭区间:等于阈值的在列表里,高一件的不在")
    void thresholdBoundaryIsInclusive() {
        setStock(5, 0);
        assertThat(findMine()).as("可售 5 == 阈值 5").isNotNull();

        setStock(6, 0);
        assertThat(findMine()).as("可售 6 已高于阈值").isNull();
    }

    @Test
    @DisplayName("停售规格与下架商品都不进预警:那不是缺货,是已经不卖了")
    void excludesRetiredSkuAndOfflineGoods() {
        setStock(1, 0);
        assertThat(findMine()).isNotNull();

        updateSku(sku -> sku.setStatus(0));
        assertThat(findMine()).as("停售规格不该一直在预警里").isNull();

        updateSku(sku -> sku.setStatus(1));
        updateGoodsStatus(0);
        assertThat(findMine()).as("下架商品留着低库存不是问题").isNull();
    }

    @Test
    @DisplayName("阈值改字典即时生效,不是写死在代码里")
    void thresholdComesFromDict() {
        setStock(3, 0);
        assertThat(findMine()).isNotNull();

        inTenant(() -> {
            updateThreshold("1");
            return null;
        });

        assertThat(inTenant(() -> stockWarnService.page(1, 10).threshold())).isEqualTo(1);
        assertThat(findMine()).as("阈值收到 1 之后,可售 3 就不再算低库存").isNull();
    }

    @Test
    @DisplayName("最该处理的排在最前面:按可售库存升序")
    void ordersByAvailableStockAsc() {
        setStock(4, 0);
        Long urgent = newSku(1, 0);

        List<Long> ids = inTenant(() -> stockWarnService.page(1, 500).page().list().stream()
                .map(StockWarnView::skuId).toList());

        assertThat(ids.indexOf(urgent)).as("可售 1 的规格要排在可售 4 之前").isGreaterThanOrEqualTo(0)
                .isLessThan(ids.indexOf(skuId));
    }

    // ——— 辅助 ———

    /** 基类夹具:商品与 SKU 都是 10 件、可售 10。阈值种子为 5。 */
    private StockWarnView findMine() {
        return inTenant(() -> stockWarnService.page(1, 500).page().list().stream()
                .filter(row -> skuId.equals(row.skuId()))
                .findFirst().orElse(null));
    }

    private void setStock(int stock, int lockedStock) {
        updateSku(sku -> {
            sku.setStock(stock);
            sku.setLockedStock(lockedStock);
        });
    }

    private void updateSku(java.util.function.Consumer<MallSku> change) {
        inTenant(() -> {
            MallSku sku = skuRepository.findById(skuId).orElseThrow();
            change.accept(sku);
            skuRepository.save(sku);
            return null;
        });
    }

    private void updateGoodsStatus(int status) {
        inTenant(() -> {
            MallGoods goods = goodsRepository.findById(goodsId).orElseThrow();
            goods.setStatus(status);
            goodsRepository.save(goods);
            return null;
        });
    }

    private void updateThreshold(String value) {
        dictService.updateData(thresholdDataId,
                new DictDataSaveRequest(THRESHOLD_DICT, "可售库存不高于该值即预警", value, 1));
    }

    private Long newSku(int stock, int lockedStock) {
        return inTenant(() -> {
            MallSku sku = new MallSku();
            sku.setGoodsId(goodsId);
            sku.setSkuCode("IT-WARN-" + System.nanoTime());
            sku.setSkuName("预警用例规格");
            sku.setPrice(new BigDecimal("50.00"));
            sku.setStock(stock);
            sku.setLockedStock(lockedStock);
            sku.setStatus(1);
            Long id = skuRepository.save(sku).getId();
            extraSkuIds.add(id);
            return id;
        });
    }
}
