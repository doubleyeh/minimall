package com.minimall.mall.service.impl;

import com.minimall.api.mall.dto.CategoryTreeNode;
import com.minimall.api.mall.dto.ClientGoodsDetailView;
import com.minimall.api.mall.dto.ClientGoodsView;
import com.minimall.api.mall.dto.ClientSkuView;
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
import com.minimall.mall.domain.QMallGoods;
import com.minimall.mall.domain.repository.MallGoodsCategoryRepository;
import com.minimall.mall.domain.repository.MallGoodsImageRepository;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.domain.repository.MallGoodsSpecRepository;
import com.minimall.mall.domain.repository.MallGoodsSpecValueRepository;
import com.minimall.mall.domain.repository.MallSkuRepository;
import com.minimall.mall.domain.repository.MallSkuSpecValueRepository;
import com.minimall.mall.service.ClientCatalogService;
import com.querydsl.core.BooleanBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小程序端商品浏览实现(商城设计文档 3.2)。
 */
@Service
@Transactional
public class ClientCatalogServiceImpl implements ClientCatalogService {

    private static final int STATUS_ON_SHELF = 1;
    private static final int STATUS_ENABLED = 1;

    private final MallGoodsRepository goodsRepository;
    private final MallGoodsCategoryRepository categoryRepository;
    private final MallGoodsImageRepository imageRepository;
    private final MallGoodsSpecRepository specRepository;
    private final MallGoodsSpecValueRepository specValueRepository;
    private final MallSkuRepository skuRepository;
    private final MallSkuSpecValueRepository skuSpecValueRepository;

    public ClientCatalogServiceImpl(MallGoodsRepository goodsRepository,
                                    MallGoodsCategoryRepository categoryRepository,
                                    MallGoodsImageRepository imageRepository,
                                    MallGoodsSpecRepository specRepository,
                                    MallGoodsSpecValueRepository specValueRepository,
                                    MallSkuRepository skuRepository,
                                    MallSkuSpecValueRepository skuSpecValueRepository) {
        this.goodsRepository = goodsRepository;
        this.categoryRepository = categoryRepository;
        this.imageRepository = imageRepository;
        this.specRepository = specRepository;
        this.specValueRepository = specValueRepository;
        this.skuRepository = skuRepository;
        this.skuSpecValueRepository = skuSpecValueRepository;
    }

    @Override
    public List<CategoryTreeNode> categories() {
        List<MallGoodsCategory> enabled = categoryRepository.findByOrderBySortOrderAscIdAsc().stream()
                .filter(category -> category.getStatus() != null && category.getStatus() == STATUS_ENABLED)
                .toList();
        Map<Long, List<MallGoodsCategory>> childrenByParent = new LinkedHashMap<>();
        for (MallGoodsCategory category : enabled) {
            childrenByParent.computeIfAbsent(category.getParentId(), key -> new ArrayList<>()).add(category);
        }
        return buildTree(0L, childrenByParent);
    }

    @Override
    public PageResult<ClientGoodsView> goods(Long categoryId, String keyword, int pageNo, int pageSize) {
        QMallGoods qGoods = QMallGoods.mallGoods;
        BooleanBuilder where = new BooleanBuilder();
        where.and(qGoods.status.eq(STATUS_ON_SHELF));
        if (categoryId != null) {
            // 一级分类要能带出它下面的二级分类商品,否则端上点一级分类会是空的
            List<Long> categoryIds = new ArrayList<>();
            categoryIds.add(categoryId);
            categoryRepository.findByParentIdOrderBySortOrderAscIdAsc(categoryId)
                    .forEach(child -> categoryIds.add(child.getId()));
            where.and(qGoods.categoryId.in(categoryIds));
        }
        if (keyword != null && !keyword.isBlank()) {
            where.and(qGoods.goodsName.contains(keyword));
        }
        Page<MallGoods> page = goodsRepository.findAll(where,
                PageRequest.of(Math.max(pageNo - 1, 0), Math.max(pageSize, 1)));
        List<ClientGoodsView> views = page.getContent().stream()
                .map(goods -> new ClientGoodsView(goods.getId(), goods.getGoodsName(), goods.getGoodsSubtitle(),
                        goods.getMainImage(), goods.getSalePriceMin(), goods.getSalePriceMax(), goods.getSaleCount()))
                .toList();
        return PageResult.of(page.getTotalElements(), views);
    }

    @Override
    public ClientGoodsDetailView detail(Long goodsId) {
        MallGoods goods = goodsRepository.findById(goodsId)
                // 下架与不存在返回同一个结果:端上依次点进来的旧链接不该暴露"这个商品曾经存在"
                .filter(item -> item.getStatus() != null && item.getStatus() == STATUS_ON_SHELF)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "商品不存在或已下架"));

        List<String> images = imageRepository.findByGoodsIdOrderBySortOrderAscIdAsc(goodsId).stream()
                .map(MallGoodsImage::getImageUrl).toList();

        List<MallGoodsSpec> specs = specRepository.findByGoodsIdOrderBySortOrderAscIdAsc(goodsId);
        Map<Long, List<MallGoodsSpecValue>> valuesBySpec = new LinkedHashMap<>();
        if (!specs.isEmpty()) {
            specValueRepository.findBySpecIdInOrderBySortOrderAscIdAsc(
                            specs.stream().map(MallGoodsSpec::getId).toList())
                    .forEach(value -> valuesBySpec.computeIfAbsent(value.getSpecId(), key -> new ArrayList<>())
                            .add(value));
        }
        List<ClientGoodsDetailView.SpecGroup> specGroups = specs.stream()
                .map(spec -> new ClientGoodsDetailView.SpecGroup(spec.getSpecName(),
                        valuesBySpec.getOrDefault(spec.getId(), List.of()).stream()
                                .map(MallGoodsSpecValue::getSpecValue).toList()))
                .toList();

        List<MallSku> enabledSkus = skuRepository.findByGoodsIdOrderByIdAsc(goodsId).stream()
                .filter(sku -> sku.getStatus() != null && sku.getStatus() == STATUS_ENABLED)
                .toList();
        Map<Long, List<String>> specValueNames = specValueNamesBySku(
                enabledSkus.stream().map(MallSku::getId).toList());
        List<ClientSkuView> skuViews = enabledSkus.stream()
                .map(sku -> new ClientSkuView(sku.getId(), sku.getSkuName(), sku.getSkuImage(), sku.getPrice(),
                        sku.availableStock(), specValueNames.getOrDefault(sku.getId(), List.of())))
                .toList();

        // 可售总库存按 availableStock 求和:直接汇总 stock 会把已被别人锁定的货算成可卖(3.2)
        int totalStock = enabledSkus.stream().mapToInt(MallSku::availableStock).sum();
        return new ClientGoodsDetailView(goods.getId(), goods.getGoodsName(), goods.getGoodsSubtitle(),
                goods.getMainImage(), goods.getDetailContent(), goods.getSalePriceMin(), goods.getSalePriceMax(),
                totalStock, goods.getSaleCount(), images, specGroups, skuViews);
    }

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

    private List<CategoryTreeNode> buildTree(long parentId, Map<Long, List<MallGoodsCategory>> childrenByParent) {
        return childrenByParent.getOrDefault(parentId, List.of()).stream()
                .map(category -> new CategoryTreeNode(category.getId(), category.getParentId(),
                        category.getCategoryName(), category.getIcon(), category.getSortOrder(),
                        category.getStatus(), buildTree(category.getId(), childrenByParent)))
                .toList();
    }
}
