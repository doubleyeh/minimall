package com.minimall.mall.service.impl;

import com.minimall.mall.api.dto.CategorySaveRequest;
import com.minimall.mall.api.dto.CategoryTreeNode;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.domain.MallGoodsCategory;
import com.minimall.mall.domain.repository.MallGoodsCategoryRepository;
import com.minimall.mall.domain.repository.MallGoodsRepository;
import com.minimall.mall.service.GoodsCategoryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品分类实现(商城设计文档 3.2)。
 *
 * <p>所有查询都靠 Hibernate 租户过滤器限定在当前租户 —— 仓储的 {@code findBy...} 方法
 * 没有一个带 {@code tenantId} 参数,但也不会读到别的租户的数据(前提是上下文正确,见 4.1)。
 * 这是本项目的统一风格:业务查询不手写 tenant_id 条件,避免"有的地方写、有的地方忘"。
 */
@Service
@Transactional
public class GoodsCategoryServiceImpl implements GoodsCategoryService {

    /** 一级分类的父 ID。 */
    private static final long ROOT_PARENT_ID = 0L;

    private final MallGoodsCategoryRepository categoryRepository;
    private final MallGoodsRepository goodsRepository;

    public GoodsCategoryServiceImpl(MallGoodsCategoryRepository categoryRepository,
                                    MallGoodsRepository goodsRepository) {
        this.categoryRepository = categoryRepository;
        this.goodsRepository = goodsRepository;
    }

    @Override
    public List<CategoryTreeNode> tree(Integer status) {
        List<MallGoodsCategory> all = categoryRepository.findByOrderBySortOrderAscIdAsc().stream()
                .filter(category -> status == null || status.equals(category.getStatus()))
                .toList();
        Map<Long, List<MallGoodsCategory>> childrenByParent = new LinkedHashMap<>();
        for (MallGoodsCategory category : all) {
            childrenByParent.computeIfAbsent(category.getParentId(), key -> new ArrayList<>()).add(category);
        }
        return buildChildren(ROOT_PARENT_ID, childrenByParent);
    }

    @Override
    public Long create(CategorySaveRequest request) {
        long parentId = normalizeParentId(request.parentId());
        requireValidParent(parentId);
        if (categoryRepository.existsByParentIdAndCategoryName(parentId, request.categoryName())) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "同级下已存在同名分类");
        }
        MallGoodsCategory category = new MallGoodsCategory();
        category.setParentId(parentId);
        category.setCategoryName(request.categoryName());
        category.setIcon(request.icon());
        category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        category.setStatus(request.status() == null ? 1 : request.status());
        return categoryRepository.save(category).getId();
    }

    @Override
    public void update(Long categoryId, CategorySaveRequest request) {
        MallGoodsCategory category = load(categoryId);
        long parentId = normalizeParentId(request.parentId());
        requireValidParent(parentId);
        if (parentId != ROOT_PARENT_ID && parentId == categoryId) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "上级分类不能是自己");
        }
        if (categoryRepository.existsByParentIdAndCategoryNameAndIdNot(parentId, request.categoryName(), categoryId)) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "同级下已存在同名分类");
        }
        category.setParentId(parentId);
        category.setCategoryName(request.categoryName());
        category.setIcon(request.icon());
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        if (request.status() != null) {
            category.setStatus(request.status());
        }
    }

    @Override
    public void delete(Long categoryId) {
        MallGoodsCategory category = load(categoryId);
        if (categoryRepository.existsByParentId(categoryId)) {
            // 不级联删除:分类被静默裁剪后,原本挂在下级的商品会变成"没有分类"的孤儿数据
            throw new BusinessException(ErrorCode.DATA_CONFLICT, "存在下级分类,请先处理下级分类");
        }
        if (goodsRepository.existsByCategoryId(categoryId)) {
            long count = goodsRepository.countByCategoryId(categoryId);
            throw new BusinessException(ErrorCode.DATA_CONFLICT,
                    "该分类下仍有 " + count + " 个商品,请先调整商品分类");
        }
        categoryRepository.delete(category);
    }

    /** 分类只允许两级:二级分类的 parentId 必须是一级分类(它自己的 parentId 必须是 0)。 */
    private void requireValidParent(long parentId) {
        if (parentId == ROOT_PARENT_ID) {
            return;
        }
        MallGoodsCategory parent = categoryRepository.findById(parentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "上级分类不存在"));
        if (parent.getParentId() != ROOT_PARENT_ID) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "分类最多两级,不能挂在二级分类下");
        }
    }

    private long normalizeParentId(Long parentId) {
        return parentId == null ? ROOT_PARENT_ID : parentId;
    }

    private MallGoodsCategory load(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分类不存在"));
    }

    private List<CategoryTreeNode> buildChildren(long parentId, Map<Long, List<MallGoodsCategory>> childrenByParent) {
        return childrenByParent.getOrDefault(parentId, List.of()).stream()
                .map(category -> new CategoryTreeNode(category.getId(), category.getParentId(),
                        category.getCategoryName(), category.getIcon(), category.getSortOrder(),
                        category.getStatus(), buildChildren(category.getId(), childrenByParent)))
                .toList();
    }
}
