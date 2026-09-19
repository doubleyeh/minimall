package com.minimall.api.mall;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.api.mall.dto.CategorySaveRequest;
import com.minimall.api.mall.dto.CategoryTreeNode;
import com.minimall.common.ApiResponse;
import com.minimall.infra.audit.AuditLog;
import com.minimall.mall.service.GoodsCategoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家管理端 - 商品分类(商城设计文档第 1 节:管理端接口前缀 {@code /mall/admin})。
 *
 * <p>权限码 {@code mall:category:*} 在 V4 迁移里已写入 {@code sys_menu}({@code is_platform = 0}),
 * 随套餐授权给租户,所以租户管理员只要被授予对应角色就能维护自己的分类。
 */
@RestController
@RequestMapping("/mall/admin/categories")
public class MallCategoryController {

    private final GoodsCategoryService categoryService;

    public MallCategoryController(GoodsCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/tree")
    @SaCheckPermission("mall:category:list")
    public ApiResponse<List<CategoryTreeNode>> tree(@RequestParam(required = false) Integer status) {
        return ApiResponse.ok(categoryService.tree(status));
    }

    @AuditLog(module = "商品分类", permCode = "mall:category:create")
    @PostMapping
    @SaCheckPermission("mall:category:create")
    public ApiResponse<Long> create(@Valid @RequestBody CategorySaveRequest request) {
        return ApiResponse.ok(categoryService.create(request));
    }

    @AuditLog(module = "商品分类", permCode = "mall:category:update")
    @PutMapping("/{categoryId}")
    @SaCheckPermission("mall:category:update")
    public ApiResponse<Void> update(@PathVariable Long categoryId,
                                    @Valid @RequestBody CategorySaveRequest request) {
        categoryService.update(categoryId, request);
        return ApiResponse.ok();
    }

    @AuditLog(module = "商品分类", permCode = "mall:category:delete")
    @DeleteMapping("/{categoryId}")
    @SaCheckPermission("mall:category:delete")
    public ApiResponse<Void> delete(@PathVariable Long categoryId) {
        categoryService.delete(categoryId);
        return ApiResponse.ok();
    }
}
