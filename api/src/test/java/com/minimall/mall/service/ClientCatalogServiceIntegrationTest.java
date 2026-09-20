package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.common.PageResult;
import com.minimall.mall.api.dto.CategorySaveRequest;
import com.minimall.mall.api.dto.CategoryTreeNode;
import com.minimall.mall.api.dto.ClientGoodsDetailView;
import com.minimall.mall.api.dto.ClientGoodsView;
import com.minimall.mall.api.dto.GoodsSaveRequest;
import com.minimall.mall.api.dto.SkuSaveRequest;
import com.minimall.mall.api.dto.SpecSaveRequest;
import com.minimall.mall.domain.MallGoodsCategory;
import com.minimall.mall.domain.repository.MallGoodsCategoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 小程序端商品浏览(商城设计文档 3.2)。
 *
 * <p>这个类此前没有测试:它的读取路径被订单用例间接带到,但**面向买家**的那几条关键过滤
 * 没有任何保护。它们每一条错了都不会报错,只会让买家看到不该看到的东西:
 *
 * <ol>
 *   <li><b>只看上架商品与启用分类</b> —— 下架商品还能被搜到、停用分类还挂在树上</li>
 *   <li><b>下架与不存在返回同一句提示</b> —— 区分开会暴露"这个商品曾经存在"</li>
 *   <li><b>可售库存按 availableStock 求和</b> —— 直接汇总 stock 会把别人已锁定的货算成可卖,
 *       买家下单后才发现没货(3.2 明确写了这条)</li>
 *   <li><b>点一级分类要能带出二级分类的商品</b> —— 否则端上点一级分类是空的</li>
 * </ol>
 */
class ClientCatalogServiceIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private ClientCatalogService clientCatalogService;
    @Autowired
    private GoodsService goodsService;
    @Autowired
    private GoodsCategoryService categoryService;
    @Autowired
    private MallGoodsCategoryRepository categoryRepository;

    private final List<Long> createdGoodsIds = new ArrayList<>();
    private final List<Long> createdCategoryIds = new ArrayList<>();

    @AfterEach
    void cleanCatalogData() {
        inTenant(() -> {
            // 先商品后分类:分类删除会拒绝"仍有商品的分类";分类再按子后父的顺序删
            for (Long goodsId : new ArrayList<>(createdGoodsIds)) {
                try {
                    goodsService.delete(goodsId);
                } catch (Exception ignored) {
                    // 已被用例自己删掉
                }
            }
            createdGoodsIds.clear();
            for (int i = createdCategoryIds.size() - 1; i >= 0; i--) {
                try {
                    categoryService.delete(createdCategoryIds.get(i));
                } catch (Exception ignored) {
                    // 同上
                }
            }
            createdCategoryIds.clear();
            return null;
        });
    }

    // ---------------------------------------------------------------- 夹具

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private Long createCategory(Long parentId, String name, int status) {
        Long id = inTenant(() -> categoryService.create(new CategorySaveRequest(parentId, name, null, null, status)));
        createdCategoryIds.add(id);
        return id;
    }

    private Long createGoods(Long categoryId, String name, Integer status, String skuCode, String price, int stock,
                             List<String> images, List<SpecSaveRequest> specs, List<String> skuSpecValues) {
        GoodsSaveRequest request = new GoodsSaveRequest(categoryId, name, "副标题", "https://example.com/main.png",
                "<p>详情</p>", null, 1, status, images, specs,
                List.of(new SkuSaveRequest(null, skuCode, "规格" + skuCode, null, new BigDecimal(price), null,
                        stock, null, null, skuSpecValues)));
        Long id = inTenant(() -> goodsService.create(request));
        createdGoodsIds.add(id);
        return id;
    }

    private Long createSimpleGoods(Long categoryId, String name, Integer status) {
        return createGoods(categoryId, name, status, "CC-" + suffix(), "12.00", 5, null, null, null);
    }

    // ================================================================ 分类树

    @Test
    @DisplayName("分类树:只给启用分类,停用的一级会带着整枝一起消失")
    void categoriesOnlyReturnsEnabledTree() {
        Long parentId = createCategory(null, "一级" + suffix(), 1);
        Long enabledChildId = createCategory(parentId, "二级启用" + suffix(), 1);
        Long disabledChildId = createCategory(parentId, "二级停用" + suffix(), 0);
        Long disabledParentId = createCategory(null, "一级停用" + suffix(), 0);
        // 停用父级下挂一个启用子级:它同样不该出现 —— 父级不在树里,子树就无从挂载
        Long orphanChildId = createCategory(disabledParentId, "停用父下的启用子级" + suffix(), 1);

        List<CategoryTreeNode> tree = inTenant(() -> clientCatalogService.categories());
        CategoryTreeNode parent = tree.stream().filter(node -> node.id().equals(parentId)).findFirst().orElseThrow();
        assertThat(parent.children()).extracting(CategoryTreeNode::id).containsExactly(enabledChildId);

        assertThat(tree).extracting(CategoryTreeNode::id)
                .as("停用的一级分类不能出现在端上")
                .doesNotContain(disabledParentId);
        assertThat(tree).extracting(CategoryTreeNode::id).doesNotContain(disabledChildId, orphanChildId);
    }

    // ================================================================ 商品列表

    @Test
    @DisplayName("商品列表:只给上架商品;点一级分类要带出它的二级分类商品")
    void goodsOnlyExposesOnShelfAndExpandsFirstLevelCategory() {
        Long parentId = createCategory(null, "列表一级" + suffix(), 1);
        Long childId = createCategory(parentId, "列表二级" + suffix(), 1);
        String keyword = "目录用例" + suffix();

        Long parentGoods = createSimpleGoods(parentId, keyword + "挂一级", 1);
        Long childGoods = createSimpleGoods(childId, keyword + "挂二级", 1);
        Long offShelf = createSimpleGoods(childId, keyword + "已下架", 0);

        PageResult<ClientGoodsView> byParent = inTenant(() -> clientCatalogService.goods(parentId, null, 1, 20));
        assertThat(byParent.list()).extracting(ClientGoodsView::id)
                .as("点一级分类却看不到二级分类的商品,端上这一级就是空的")
                .containsExactlyInAnyOrder(parentGoods, childGoods);
        assertThat(byParent.list()).extracting(ClientGoodsView::id).doesNotContain(offShelf);

        PageResult<ClientGoodsView> byChild = inTenant(() -> clientCatalogService.goods(childId, null, 1, 20));
        assertThat(byChild.list()).extracting(ClientGoodsView::id)
                .as("点二级分类不该把父级直属的商品也带出来")
                .containsExactly(childGoods);

        PageResult<ClientGoodsView> byKeyword = inTenant(() -> clientCatalogService.goods(null, keyword, 1, 20));
        assertThat(byKeyword.list()).extracting(ClientGoodsView::id)
                .containsExactlyInAnyOrder(parentGoods, childGoods);

        assertThat(inTenant(() -> clientCatalogService.goods(null, "不存在" + suffix(), 1, 20)).total()).isZero();
    }

    // ================================================================ 商品详情

    @Test
    @DisplayName("详情:下架与不存在返回同一句提示(不暴露这个商品曾经存在)")
    void detailHidesOffShelfAndUnknown() {
        Long categoryId = createCategory(null, "详情分类" + suffix(), 1);
        Long offShelf = createSimpleGoods(categoryId, "详情已下架" + suffix(), 0);

        assertThatThrownBy(() -> inTenant(() -> clientCatalogService.detail(offShelf)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("商品不存在或已下架");
        assertThatThrownBy(() -> inTenant(() -> clientCatalogService.detail(999999L)))
                .isInstanceOf(BusinessException.class)
                .as("两种情况给不同提示会暴露商品曾存在")
                .hasMessageContaining("商品不存在或已下架");
    }

    @Test
    @DisplayName("详情:可售库存按 availableStock 求和,别人锁定的货不能算成可卖")
    void detailCountsAvailableStockRatherThanRawStock() {
        // 夹具商品:单价 50、库存 10、无锁定
        assertThat(inTenant(() -> clientCatalogService.detail(goodsId)).totalStock())
                .as("初始可售就是库存本身")
                .isEqualTo(10);

        // 下单一笔 3 件:库存被锁定,但 sku.stock 仍是 10
        createOrder(customerId, addressId, 3);
        inTenant(() -> {
            assertThat(skuRepository.findById(skuId).orElseThrow().getStock())
                    .as("锁定不动 stock,只动 locked_stock")
                    .isEqualTo(10);
            return null;
        });

        assertThat(inTenant(() -> clientCatalogService.detail(goodsId)).totalStock())
                .as("直接汇总 stock 会把它算成 10,买家下单后才发现没货")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("详情:规格分组与每个 SKU 的规格值名都要返回(端上靠它渲染选择器)")
    void detailExposesSpecsAndSkuSpecValues() {
        Long categoryId = createCategory(null, "规格分类" + suffix(), 1);
        String skuCode = "SPEC-" + suffix();
        Long goodsId = createGoods(categoryId, "带规格的商品" + suffix(), 1, skuCode, "9.90", 3,
                List.of("https://example.com/1.png", "https://example.com/2.png"),
                List.of(new SpecSaveRequest("颜色", List.of("红", "蓝"))),
                List.of("红"));

        ClientGoodsDetailView detail = inTenant(() -> clientCatalogService.detail(goodsId));
        assertThat(detail.images()).as("轮播图按排序返回").containsExactly(
                "https://example.com/1.png", "https://example.com/2.png");
        assertThat(detail.specs()).hasSize(1);
        assertThat(detail.specs().get(0).specName()).isEqualTo("颜色");
        assertThat(detail.specs().get(0).values())
                .as("规格组要给全部取值,端上才有东西可选")
                .containsExactly("红", "蓝");
        assertThat(detail.skus()).hasSize(1);
        assertThat(detail.skus().get(0).specValues())
                .as("SKU 要带自己的规格值名,端上才能按选择过滤")
                .containsExactly("红");
        assertThat(detail.skus().get(0).availableStock()).isEqualTo(3);
    }

    @Test
    @DisplayName("详情:没有规格的商品也要正常返回(规格与关联都为空,不能抛异常)")
    void detailHandlesGoodsWithoutSpecs() {
        ClientGoodsDetailView detail = inTenant(() -> clientCatalogService.detail(goodsId));

        assertThat(detail.specs()).isEmpty();
        assertThat(detail.skus()).hasSize(1);
        assertThat(detail.skus().get(0).specValues()).isEmpty();
        assertThat(detail.goodsName()).isNotBlank();
    }

    @Test
    @DisplayName("详情:停售的 SKU 不出现,也不计入可售库存")
    void detailHidesDisabledSkus() {
        Long categoryId = createCategory(null, "停售SKU分类" + suffix(), 1);
        String enabledCode = "EN-" + suffix();
        String disabledCode = "DIS-" + suffix();
        Long goodsId = inTenant(() -> goodsService.create(new GoodsSaveRequest(
                categoryId, "另有一个停售 SKU" + suffix(), null, "https://example.com/main.png", null, null,
                1, 1, null, null,
                List.of(new SkuSaveRequest(null, enabledCode, "在售规格", null, new BigDecimal("10.00"), null,
                                2, null, 1, List.of()),
                        new SkuSaveRequest(null, disabledCode, "停售规格", null, new BigDecimal("1.00"), null,
                                99, null, 0, List.of())))));
        createdGoodsIds.add(goodsId);

        ClientGoodsDetailView detail = inTenant(() -> clientCatalogService.detail(goodsId));
        assertThat(detail.skus()).hasSize(1);
        assertThat(detail.skus().get(0).skuName()).isEqualTo("在售规格");
        assertThat(detail.totalStock())
                .as("停售 SKU 的 99 件不能算进可售库存")
                .isEqualTo(2);
    }
}
