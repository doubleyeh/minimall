package com.minimall.mall.service.impl;

import com.minimall.mall.api.dto.GoodsDetailView;
import com.minimall.mall.api.dto.GoodsSaveRequest;
import com.minimall.mall.api.dto.GoodsView;
import com.minimall.mall.api.dto.SkuSaveRequest;
import com.minimall.mall.api.dto.SkuView;
import com.minimall.mall.api.dto.SpecSaveRequest;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallGoodsCategory;
import com.minimall.mall.domain.MallGoodsImage;
import com.minimall.mall.domain.MallGoodsSpec;
import com.minimall.mall.domain.MallGoodsSpecValue;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.MallSkuSpecValue;
import com.minimall.mall.domain.MallStockLog;
import com.minimall.mall.domain.QMallGoods;
import com.minimall.mall.domain.repository.MallFreightTemplateRepository;
import com.minimall.mall.domain.repository.MallGoodsCategoryRepository;
import com.minimall.mall.domain.repository.MallGoodsImageRepository;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallGoodsSpecRepository;
import com.minimall.mall.domain.repository.MallGoodsSpecValueRepository;
import com.minimall.mall.domain.repository.MallOrderItemRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.domain.repository.MallSkuSpecValueRepository;
import com.minimall.mall.domain.repository.MallStockLogRepository;
import com.minimall.mall.service.GoodsService;
import com.minimall.infra.tenant.TenantContext;
import com.querydsl.core.BooleanBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 商品与 SKU 实现(商城设计文档 3.2)。
 *
 * <p>三处需要特别小心的逻辑:
 * <ol>
 *   <li><b>汇总字段重算</b>({@link #recalcSummary}):只统计启用的 SKU,任何 SKU 变更后都要调用</li>
 *   <li><b>SKU 不删除</b>:修改时未提交的旧 SKU 置为停售。历史订单/售后要能回查到它,
 *       物理删除会让 {@code mall_order_item.sku_id} 指向不存在的行</li>
 *   <li><b>规格关联重建</b>:规格是全量替换的,所以每个 SKU 的规格值关联也必须重建 ——
 *       否则旧关联会指向已被删除的规格值</li>
 * </ol>
 */
@Service
@Transactional
public class GoodsServiceImpl implements GoodsService {

    private final MallGoodsRepository goodsRepository;
    private final MallGoodsImageRepository imageRepository;
    private final MallGoodsSpecRepository specRepository;
    private final MallGoodsSpecValueRepository specValueRepository;
    private final MallSkuRepository skuRepository;
    private final MallSkuSpecValueRepository skuSpecValueRepository;
    private final MallStockLogRepository stockLogRepository;
    private final MallGoodsCategoryRepository categoryRepository;
    private final MallFreightTemplateRepository freightTemplateRepository;
    private final MallOrderItemRepository orderItemRepository;

    public GoodsServiceImpl(MallGoodsRepository goodsRepository,
                            MallGoodsImageRepository imageRepository,
                            MallGoodsSpecRepository specRepository,
                            MallGoodsSpecValueRepository specValueRepository,
                            MallSkuRepository skuRepository,
                            MallSkuSpecValueRepository skuSpecValueRepository,
                            MallStockLogRepository stockLogRepository,
                            MallGoodsCategoryRepository categoryRepository,
                            MallFreightTemplateRepository freightTemplateRepository,
                            MallOrderItemRepository orderItemRepository) {
        this.goodsRepository = goodsRepository;
        this.imageRepository = imageRepository;
        this.specRepository = specRepository;
        this.specValueRepository = specValueRepository;
        this.skuRepository = skuRepository;
        this.skuSpecValueRepository = skuSpecValueRepository;
        this.stockLogRepository = stockLogRepository;
        this.categoryRepository = categoryRepository;
        this.freightTemplateRepository = freightTemplateRepository;
        this.orderItemRepository = orderItemRepository;
    }

    @Override
    public PageResult<GoodsView> page(String goodsName, Long categoryId, Integer status, int pageNo, int pageSize) {
        QMallGoods qGoods = QMallGoods.mallGoods;
        BooleanBuilder where = new BooleanBuilder();
        if (goodsName != null && !goodsName.isBlank()) {
            where.and(qGoods.goodsName.contains(goodsName));
        }
        if (categoryId != null) {
            // 点一级分类要看它下面二级分类的商品,与客户端商品列表同一套口径
            List<Long> categoryIds = new ArrayList<>();
            categoryIds.add(categoryId);
            categoryRepository.findByParentIdOrderBySortOrderAscIdAsc(categoryId)
                    .forEach(child -> categoryIds.add(child.getId()));
            where.and(qGoods.categoryId.in(categoryIds));
        }
        if (status != null) {
            where.and(qGoods.status.eq(status));
        }
        Page<MallGoods> page = goodsRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1)));
        Map<Long, String> categoryNames = categoryNames(page.getContent().stream()
                .map(MallGoods::getCategoryId).toList());
        List<GoodsView> views = page.getContent().stream()
                .map(goods -> new GoodsView(goods.getId(), goods.getCategoryId(),
                        categoryNames.get(goods.getCategoryId()), goods.getGoodsName(),
                        goods.getGoodsSubtitle(), goods.getMainImage(), goods.getSalePriceMin(),
                        goods.getSalePriceMax(), goods.getTotalStock(), goods.getSaleCount(),
                        goods.getStatus(), goods.getSortOrder()))
                .toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public GoodsDetailView detail(Long goodsId) {
        MallGoods goods = load(goodsId);
        List<String> images = imageRepository.findByGoodsIdOrderBySortOrderAscIdAsc(goodsId).stream()
                .map(MallGoodsImage::getImageUrl).toList();
        List<MallGoodsSpec> specs = specRepository.findByGoodsIdOrderBySortOrderAscIdAsc(goodsId);
        Map<Long, List<MallGoodsSpecValue>> valuesBySpec = specValuesBySpec(
                specs.stream().map(MallGoodsSpec::getId).toList());
        List<GoodsDetailView.SpecView> specViews = specs.stream()
                .map(spec -> new GoodsDetailView.SpecView(spec.getId(), spec.getSpecName(), spec.getSortOrder(),
                        valuesBySpec.getOrDefault(spec.getId(), List.of()).stream()
                                .map(value -> new GoodsDetailView.SpecValueView(
                                        value.getId(), value.getSpecValue(), value.getSortOrder()))
                                .toList()))
                .toList();

        List<MallSku> skus = skuRepository.findByGoodsIdOrderByIdAsc(goodsId);
        Map<Long, List<String>> specValueNamesBySku = specValueNamesBySku(
                skus.stream().map(MallSku::getId).toList());
        List<SkuView> skuViews = skus.stream().map(sku -> toSkuView(sku,
                specValueNamesBySku.getOrDefault(sku.getId(), List.of()))).toList();

        return new GoodsDetailView(goods.getId(), goods.getCategoryId(), goods.getGoodsName(),
                goods.getGoodsSubtitle(), goods.getMainImage(), goods.getDetailContent(),
                goods.getFreightTemplateId(), goods.getSalePriceMin(), goods.getSalePriceMax(),
                goods.getTotalStock(), goods.getSaleCount(), goods.getStatus(), goods.getSortOrder(),
                images, specViews, skuViews);
    }

    @Override
    public Long create(GoodsSaveRequest request) {
        validateCategory(request.categoryId());
        validateFreightTemplate(request.freightTemplateId());
        validateSkuCodesUnique(request.skus(), null);

        MallGoods goods = new MallGoods();
        applyBasicFields(goods, request);
        goods.setSaleCount(0);
        // 汇总字段先落 0,下面统一重算 —— 避免出现"商品已存但价格区间是 null"的中间态
        goods.setSalePriceMin(BigDecimal.ZERO);
        goods.setSalePriceMax(BigDecimal.ZERO);
        goods.setTotalStock(0);
        goods = goodsRepository.save(goods);

        saveImages(goods.getId(), request.images());
        Map<String, Long> specValueIds = saveSpecs(goods.getId(), request.specs());
        for (SkuSaveRequest skuRequest : request.skus()) {
            MallSku sku = new MallSku();
            sku.setGoodsId(goods.getId());
            applySkuFields(sku, skuRequest);
            sku.setLockedStock(0);
            sku = skuRepository.save(sku);
            saveSkuSpecValues(sku.getId(), skuRequest.specValues(), specValueIds);
        }
        recalcSummary(goods);
        return goods.getId();
    }

    @Override
    public void update(Long goodsId, GoodsSaveRequest request) {
        MallGoods goods = load(goodsId);
        validateCategory(request.categoryId());
        validateFreightTemplate(request.freightTemplateId());
        validateSkuCodesUnique(request.skus(), goodsId);

        applyBasicFields(goods, request);

        // 轮播图与规格都是"全量替换"的语义:管理端表单提交的就是最终形态
        replaceImages(goodsId, request.images());
        Map<String, Long> specValueIds = replaceSpecs(goodsId, request.specs());

        List<MallSku> existing = skuRepository.findByGoodsIdOrderByIdAsc(goodsId);
        Map<Long, MallSku> existingById = new LinkedHashMap<>();
        existing.forEach(sku -> existingById.put(sku.getId(), sku));
        for (SkuSaveRequest skuRequest : request.skus()) {
            MallSku sku;
            if (skuRequest.id() == null) {
                sku = new MallSku();
                sku.setGoodsId(goodsId);
                sku.setLockedStock(0);
                applySkuFields(sku, skuRequest);
                sku = skuRepository.save(sku);
            } else {
                sku = existingById.remove(skuRequest.id());
                if (sku == null) {
                    // 传了不属于这个商品的 SKU ID:要么是前端串了数据,要么是构造的越权请求
                    throw new BusinessException(ErrorCode.PARAM_INVALID, "SKU 不属于该商品");
                }
                int before = sku.getStock() == null ? 0 : sku.getStock();
                applySkuFields(sku, skuRequest);
                recordManualStockChange(sku, before);
            }
            // 规格值关联必须重建:规格是刚重建的,旧关联指向的 spec_value_id 已经不存在了
            skuSpecValueRepository.deleteBySkuId(sku.getId());
            saveSkuSpecValues(sku.getId(), skuRequest.specValues(), specValueIds);
        }
        // 剩下的就是本次没提交的 SKU:停售,而不是删除(历史订单/售后还要回查)
        existingById.values().forEach(sku -> sku.setStatus(0));

        recalcSummary(goods);
    }

    @Override
    public void changeStatus(Long goodsId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "商品状态只能是 0(下架)或 1(上架)");
        }
        MallGoods goods = load(goodsId);
        if (status == 1 && goods.getTotalStock() != null && goods.getTotalStock() <= 0) {
            // 上架一个没有库存的商品,端上点进去就是"卖不了" —— 提前挡住比让用户投诉更好
            throw new BusinessException(ErrorCode.PARAM_INVALID, "商品库存为 0,无法上架");
        }
        goods.setStatus(status);
    }

    @Override
    public void delete(Long goodsId) {
        MallGoods goods = load(goodsId);
        long referenced = orderItemRepository.countByGoodsId(goodsId);
        if (referenced > 0) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT,
                    "该商品已有 " + referenced + " 条订单记录,只能下架不能删除");
        }
        List<Long> skuIds = skuRepository.findByGoodsIdOrderByIdAsc(goodsId).stream()
                .map(MallSku::getId).toList();
        skuIds.forEach(skuSpecValueRepository::deleteBySkuId);
        skuRepository.deleteByGoodsId(goodsId);
        specRepository.findByGoodsIdOrderBySortOrderAscIdAsc(goodsId)
                .forEach(spec -> specValueRepository.deleteBySpecId(spec.getId()));
        specRepository.deleteByGoodsId(goodsId);
        imageRepository.deleteByGoodsId(goodsId);
        goodsRepository.delete(goods);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 重算冗余汇总字段(3.2)。
     *
     * <p>只统计 {@code status = 1} 的 SKU:停售的 SKU 不该影响列表页的"起售价",
     * 否则会出现"列表显示 9.9 起,点进去最便宜的规格是 99"这类问题。
     */
    private void recalcSummary(MallGoods goods) {
        List<MallSku> enabled = skuRepository.findByGoodsIdOrderByIdAsc(goods.getId()).stream()
                .filter(sku -> sku.getStatus() != null && sku.getStatus() == 1)
                .toList();
        if (enabled.isEmpty()) {
            goods.setSalePriceMin(BigDecimal.ZERO);
            goods.setSalePriceMax(BigDecimal.ZERO);
            goods.setTotalStock(0);
            return;
        }
        BigDecimal min = enabled.stream().map(MallSku::getPrice).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal max = enabled.stream().map(MallSku::getPrice).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        int totalStock = enabled.stream().mapToInt(sku -> sku.getStock() == null ? 0 : sku.getStock()).sum();
        goods.setSalePriceMin(min);
        goods.setSalePriceMax(max);
        goods.setTotalStock(totalStock);
    }

    private void applyBasicFields(MallGoods goods, GoodsSaveRequest request) {
        goods.setCategoryId(request.categoryId());
        goods.setGoodsName(request.goodsName());
        goods.setGoodsSubtitle(request.goodsSubtitle());
        goods.setMainImage(request.mainImage());
        goods.setDetailContent(request.detailContent());
        goods.setFreightTemplateId(request.freightTemplateId());
        // 缺省排序列 = 0,与 status 同样处理:库上是 NOT NULL,
        // 只在"传了才设置"会让不传 sortOrder 的新建直接撞非空约束
        // (被兜底成 50002「数据已存在或存在引用关系」,与真实原因完全对不上)。
        // 分类与部门的 create 早就是这个写法,这里对齐它们。
        if (request.sortOrder() != null) {
            goods.setSortOrder(request.sortOrder());
        } else if (goods.getId() == null) {
            goods.setSortOrder(0);
        }
        if (request.status() != null) {
            goods.setStatus(request.status());
        } else if (goods.getId() == null) {
            goods.setStatus(0);
        }
    }

    private void applySkuFields(MallSku sku, SkuSaveRequest request) {
        sku.setSkuCode(request.skuCode());
        sku.setSkuName(request.skuName());
        sku.setSkuImage(request.skuImage());
        sku.setPrice(request.price());
        sku.setCostPrice(request.costPrice());
        sku.setStock(request.stock());
        sku.setWeight(request.weight());
        if (request.status() != null) {
            sku.setStatus(request.status());
        } else if (sku.getId() == null) {
            sku.setStatus(1);
        }
    }

    private void replaceImages(Long goodsId, List<String> images) {
        imageRepository.deleteByGoodsId(goodsId);
        saveImages(goodsId, images);
    }

    private void saveImages(Long goodsId, List<String> images) {
        if (images == null || images.isEmpty()) {
            return;
        }
        int order = 0;
        for (String url : images) {
            if (url == null || url.isBlank()) {
                continue;
            }
            MallGoodsImage image = new MallGoodsImage();
            image.setGoodsId(goodsId);
            image.setImageUrl(url);
            image.setSortOrder(order++);
            imageRepository.save(image);
        }
    }

    /** 规格全量替换:先清掉旧的关联与规格值,再按提交内容重建。 */
    private Map<String, Long> replaceSpecs(Long goodsId, List<SpecSaveRequest> specs) {
        List<MallGoodsSpec> oldSpecs = specRepository.findByGoodsIdOrderBySortOrderAscIdAsc(goodsId);
        List<Long> oldSpecIds = oldSpecs.stream().map(MallGoodsSpec::getId).toList();
        if (!oldSpecIds.isEmpty()) {
            specValueRepository.findBySpecIdInOrderBySortOrderAscIdAsc(oldSpecIds)
                    .forEach(value -> skuSpecValueRepository.deleteBySpecValueId(value.getId()));
            oldSpecIds.forEach(specValueRepository::deleteBySpecId);
        }
        specRepository.deleteByGoodsId(goodsId);
        return saveSpecs(goodsId, specs);
    }

    /**
     * 保存规格与规格值,返回"规格值名称 → ID"的映射,供 SKU 关联使用。
     *
     * <p>用名称做键是刻意的:前端提交 SKU 时写的就是名称组合(比 ID 更贴近表单),
     * 而同一次提交里规格值名称不会重复 —— 名称冲突在保存前会被 {@link #validateSpecValues} 挡住。
     */
    private Map<String, Long> saveSpecs(Long goodsId, List<SpecSaveRequest> specs) {
        Map<String, Long> result = new HashMap<>();
        if (specs == null || specs.isEmpty()) {
            return result;
        }
        int specOrder = 0;
        for (SpecSaveRequest specRequest : specs) {
            validateSpecValues(specRequest);
            MallGoodsSpec spec = new MallGoodsSpec();
            spec.setGoodsId(goodsId);
            spec.setSpecName(specRequest.specName());
            spec.setSortOrder(specOrder++);
            spec = specRepository.save(spec);
            int valueOrder = 0;
            for (String value : specRequest.values()) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String trimmed = value.trim();
                if (result.containsKey(trimmed)) {
                    throw new BusinessException(ErrorCode.PARAM_INVALID,
                            "规格值「" + trimmed + "」在多个规格下重复,SKU 无法区分");
                }
                MallGoodsSpecValue specValue = new MallGoodsSpecValue();
                specValue.setSpecId(spec.getId());
                specValue.setSpecValue(trimmed);
                specValue.setSortOrder(valueOrder++);
                result.put(trimmed, specValueRepository.save(specValue).getId());
            }
        }
        return result;
    }

    private void validateSpecValues(SpecSaveRequest specRequest) {
        List<String> values = specRequest.values();
        long distinct = values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).distinct().count();
        if (distinct == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "规格「" + specRequest.specName() + "」至少需要一个规格值");
        }
        if (distinct != values.size()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "规格「" + specRequest.specName() + "」存在重复的规格值");
        }
    }

    private void saveSkuSpecValues(Long skuId, List<String> specValues, Map<String, Long> specValueIds) {
        if (specValues == null || specValues.isEmpty()) {
            return;
        }
        for (String name : specValues) {
            String trimmed = name == null ? null : name.trim();
            Long specValueId = trimmed == null ? null : specValueIds.get(trimmed);
            if (specValueId == null) {
                // SKU 引用了本次没有提交的规格值:关联建不起来,商品会在端上显示出"选不了"的规格组合
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "SKU 的规格值「" + name + "」不在本次提交的规格里");
            }
            MallSkuSpecValue link = new MallSkuSpecValue();
            link.setSkuId(skuId);
            link.setSpecValueId(specValueId);
            skuSpecValueRepository.save(link);
        }
    }

    /** 手工改库存写一条流水(stock_log 的 change_type=5)。库存是钱,任何变动都要留痕。 */
    private void recordManualStockChange(MallSku sku, int before) {
        int after = sku.getStock() == null ? 0 : sku.getStock();
        int delta = after - before;
        if (delta == 0) {
            return;
        }
        MallStockLog log = new MallStockLog();
        log.setSkuId(sku.getId());
        log.setChangeType(5);
        log.setChangeStock(delta);
        log.setChangeLocked(0);
        log.setRemark("管理端调整库存");
        stockLogRepository.save(log);
    }

    private void validateCategory(Long categoryId) {
        // 分类可能是二级分类,父级是否启用不影响挂载;这里只校验它存在且属于当前租户
        categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品分类不存在"));
    }

    private void validateFreightTemplate(Long freightTemplateId) {
        if (freightTemplateId == null) {
            return;
        }
        freightTemplateRepository.findById(freightTemplateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "运费模板不存在"));
    }

    /** SKU 编码在租户内唯一(靠库上 uk_tenant_sku_code 兜底,这里提前给出可读的错误)。 */
    private void validateSkuCodesUnique(List<SkuSaveRequest> skus, Long goodsId) {
        Long tenantId = TenantContext.getTenantId();
        Map<String, Integer> seen = new HashMap<>();
        for (SkuSaveRequest sku : skus) {
            String code = sku.skuCode() == null ? null : sku.skuCode().trim();
            if (code == null || code.isEmpty()) {
                continue;
            }
            if (seen.merge(code, 1, Integer::sum) > 1) {
                throw new BusinessException(ErrorCode.DATA_CONFLICT, "本次提交里 SKU 编码重复:" + code);
            }
            if (tenantId == null) {
                continue;
            }
            Optional<MallSku> existing = skuRepository.findByTenantIdAndSkuCode(tenantId, code);
            if (existing.isPresent() && (goodsId == null || !existing.get().getGoodsId().equals(goodsId))) {
                throw new BusinessException(ErrorCode.DATA_CONFLICT, "SKU 编码已被其他商品使用:" + code);
            }
        }
    }

    private MallGoods load(Long goodsId) {
        return goodsRepository.findById(goodsId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品不存在"));
    }

    private Map<Long, String> categoryNames(Collection<Long> categoryIds) {
        Map<Long, String> result = new HashMap<>();
        if (categoryIds == null || categoryIds.isEmpty()) {
            return result;
        }
        List<Long> distinct = categoryIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return result;
        }
        categoryRepository.findAllById(distinct)
                .forEach(category -> result.put(category.getId(), category.getCategoryName()));
        return result;
    }

    private Map<Long, List<MallGoodsSpecValue>> specValuesBySpec(List<Long> specIds) {
        Map<Long, List<MallGoodsSpecValue>> result = new LinkedHashMap<>();
        if (specIds.isEmpty()) {
            return result;
        }
        for (MallGoodsSpecValue value : specValueRepository.findBySpecIdInOrderBySortOrderAscIdAsc(specIds)) {
            result.computeIfAbsent(value.getSpecId(), key -> new ArrayList<>()).add(value);
        }
        return result;
    }

    /** 每个 SKU 关联的规格值名称(展示用,如"红色/XL")。 */
    private Map<Long, List<String>> specValueNamesBySku(List<Long> skuIds) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        if (skuIds.isEmpty()) {
            return result;
        }
        List<MallSkuSpecValue> links = skuSpecValueRepository.findBySkuIdIn(skuIds);
        if (links.isEmpty()) {
            return result;
        }
        Map<Long, String> nameById = new HashMap<>();
        specValueRepository.findAllById(links.stream().map(MallSkuSpecValue::getSpecValueId).distinct().toList())
                .forEach(value -> nameById.put(value.getId(), value.getSpecValue()));
        for (MallSkuSpecValue link : links) {
            String name = nameById.get(link.getSpecValueId());
            if (name != null) {
                result.computeIfAbsent(link.getSkuId(), key -> new ArrayList<>()).add(name);
            }
        }
        return result;
    }

    private SkuView toSkuView(MallSku sku, List<String> specValueNames) {
        return new SkuView(sku.getId(), sku.getSkuCode(), sku.getSkuName(), sku.getSkuImage(),
                sku.getPrice(), sku.getCostPrice(), sku.getStock(), sku.getLockedStock(),
                sku.availableStock(), sku.getWeight(), sku.getStatus(), specValueNames);
    }
}
