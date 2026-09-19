package com.minimall.mall.api;

import com.minimall.mall.api.dto.CategoryTreeNode;
import com.minimall.mall.api.dto.ClientGoodsDetailView;
import com.minimall.mall.api.dto.ClientGoodsView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.mall.service.ClientCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端 - 商品浏览(商城设计文档 3.2)。
 *
 * <p><b>这三个接口允许游客访问(不需要客户端令牌)</b>:小程序里"先逛后登录"是常态,
 * 强制登录再浏览会明显增加流失。它们仍需要 {@code X-Tenant-Code} 定位租户
 * (在 4.9 的白名单机制里放行,见 {@code ClientAuthFilter} 的公开清单)。
 *
 * <p>注意"不需要令牌"不等于"没有租户":没有租户就无法确定看的是哪个商家的商品。
 */
@RestController
@RequestMapping("/mall/api")
public class ClientCatalogController {

    private final ClientCatalogService catalogService;

    public ClientCatalogController(ClientCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/categories")
    public ApiResponse<List<CategoryTreeNode>> categories() {
        return ApiResponse.ok(catalogService.categories());
    }

    @GetMapping("/goods")
    public ApiResponse<PageResult<ClientGoodsView>> goods(@RequestParam(required = false) Long categoryId,
                                                          @RequestParam(required = false) String keyword,
                                                          @RequestParam(defaultValue = "1") int pageNo,
                                                          @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(catalogService.goods(categoryId, keyword, pageNo, pageSize));
    }

    @GetMapping("/goods/{goodsId}")
    public ApiResponse<ClientGoodsDetailView> detail(@PathVariable Long goodsId) {
        return ApiResponse.ok(catalogService.detail(goodsId));
    }
}
