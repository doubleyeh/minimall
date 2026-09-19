package com.minimall.api.mall;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.api.mall.dto.GoodsDetailView;
import com.minimall.api.mall.dto.GoodsSaveRequest;
import com.minimall.api.mall.dto.GoodsView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditLog;
import com.minimall.mall.service.GoodsService;
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

/**
 * 商家管理端 - 商品与 SKU(商城设计文档 3.2)。
 */
@RestController
@RequestMapping("/mall/admin/goods")
public class MallGoodsController {

    private final GoodsService goodsService;

    public MallGoodsController(GoodsService goodsService) {
        this.goodsService = goodsService;
    }

    @GetMapping
    @SaCheckPermission("mall:goods:list")
    public ApiResponse<PageResult<GoodsView>> page(@RequestParam(required = false) String goodsName,
                                                   @RequestParam(required = false) Long categoryId,
                                                   @RequestParam(required = false) Integer status,
                                                   @RequestParam(defaultValue = "1") int pageNo,
                                                   @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(goodsService.page(goodsName, categoryId, status, pageNo, pageSize));
    }

    @GetMapping("/{goodsId}")
    @SaCheckPermission("mall:goods:list")
    public ApiResponse<GoodsDetailView> detail(@PathVariable Long goodsId) {
        return ApiResponse.ok(goodsService.detail(goodsId));
    }

    @AuditLog(module = "商品管理", permCode = "mall:goods:create")
    @PostMapping
    @SaCheckPermission("mall:goods:create")
    public ApiResponse<Long> create(@Valid @RequestBody GoodsSaveRequest request) {
        return ApiResponse.ok(goodsService.create(request));
    }

    @AuditLog(module = "商品管理", permCode = "mall:goods:update")
    @PutMapping("/{goodsId}")
    @SaCheckPermission("mall:goods:update")
    public ApiResponse<Void> update(@PathVariable Long goodsId, @Valid @RequestBody GoodsSaveRequest request) {
        goodsService.update(goodsId, request);
        return ApiResponse.ok();
    }

    @AuditLog(module = "商品管理", permCode = "mall:goods:status")
    @PutMapping("/{goodsId}/status")
    @SaCheckPermission("mall:goods:status")
    public ApiResponse<Void> changeStatus(@PathVariable Long goodsId, @RequestParam Integer status) {
        goodsService.changeStatus(goodsId, status);
        return ApiResponse.ok();
    }

    @AuditLog(module = "商品管理", permCode = "mall:goods:delete")
    @DeleteMapping("/{goodsId}")
    @SaCheckPermission("mall:goods:delete")
    public ApiResponse<Void> delete(@PathVariable Long goodsId) {
        goodsService.delete(goodsId);
        return ApiResponse.ok();
    }
}
