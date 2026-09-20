package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.common.PageResult;
import com.minimall.mall.api.dto.CategorySaveRequest;
import com.minimall.mall.api.dto.CategoryTreeNode;
import com.minimall.mall.api.dto.CreateOrderRequest;
import com.minimall.mall.api.dto.GoodsDetailView;
import com.minimall.mall.api.dto.GoodsSaveRequest;
import com.minimall.mall.api.dto.GoodsView;
import com.minimall.mall.api.dto.SkuSaveRequest;
import com.minimall.mall.api.dto.SpecSaveRequest;
import com.minimall.mall.domain.MallGoods;
import com.minimall.mall.domain.MallGoodsCategory;
import com.minimall.mall.domain.MallGoodsSpec;
import com.minimall.mall.domain.MallGoodsSpecValue;
import com.minimall.mall.domain.MallSku;
import com.minimall.mall.domain.MallStockLog;
import com.minimall.mall.domain.repository.MallGoodsCategoryRepository;
import com.minimall.mall.domain.repository.MallGoodsImageRepository;
import com.minimall.mall.domain.repository.MallGoodsSpecRepository;
import com.minimall.mall.domain.repository.MallGoodsSpecValueRepository;
import com.minimall.mall.domain.repository.MallSkuSpecValueRepository;
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
 * 商品与分类管理的分支与边界(商城设计文档 3.2)。
 *
 * <p>{@link GoodsServiceImpl} 的主流程被管理端 HTTP 用例覆盖过,缺的都是"提交内容不合规"这类分支。
 * 其中两条是**业务规则而非参数校验**,值得单独说:
 *
 * <ol>
 *   <li><b>SKU 只停售不删除</b> —— 修改商品时未提交的旧 SKU 置为 {@code status = 0}。
 *       物理删除会让历史订单与售后单里的 {@code sku_id} 指向不存在的行,
 *       而那时买家已经在看"我买的到底是什么规格"</li>
 *   <li><b>汇总字段只看启用的 SKU</b> —— 否则列表页会出现"起售价 9.9,点进去最便宜 99"</li>
 * </ol>
 *
 * <p>本类自己造的商品与分类都在 {@code @AfterEach} 里清掉。清理顺序必须是"先商品后分类":
 * 分类删除会拒绝"仍有商品的分类",反过来清不掉。
 */
class GoodsServiceBranchIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private GoodsService goodsService;
    @Autowired
    private GoodsCategoryService categoryService;
    @Autowired
    private MallGoodsCategoryRepository categoryRepository;
    @Autowired
    private MallGoodsImageRepository goodsImageRepository;
    @Autowired
    private MallGoodsSpecRepository goodsSpecRepository;
    @Autowired
    private MallGoodsSpecValueRepository goodsSpecValueRepository;
    @Autowired
    private MallSkuSpecValueRepository skuSpecValueRepository;

    private final List<Long> createdGoodsIds = new ArrayList<>();
    private final List<Long> createdCategoryIds = new ArrayList<>();

    @AfterEach
    void cleanCreated() {
        // 先商品后分类:分类删除会拒绝"仍有商品的分类"。逆序删除是为了子分类先于父分类。
        inTenant(() -> {
            for (Long goodsId : createdGoodsIds) {
                try {
                    goodsService.delete(goodsId);
                } catch (Exception ignored) {
                    // 已被用例自身删掉、或已被订单引用:交给基类的夹具清理,不在这里较劲
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

    private Long createCategory(String name) {
        return createCategory(null, name);
    }

    private Long createCategory(Long parentId, String name) {
        Long id = inTenant(() -> categoryService.create(new CategorySaveRequest(parentId, name, null, null, 1)));
        createdCategoryIds.add(id);
        return id;
    }

    private String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }

    private SkuSaveRequest sku(String code, String price, int stock) {
        return new SkuSaveRequest(null, code, "规格" + code, null, new BigDecimal(price), null,
                stock, null, null, List.of());
    }

    private GoodsSaveRequest goodsRequest(Long categoryId, String name, Integer status, List<SkuSaveRequest> skus) {
        return new GoodsSaveRequest(categoryId, name, "副标题", "https://example.com/main.png", "<p>详情</p>",
                null, 1, status, null, null, skus);
    }

    private GoodsSaveRequest withSpecs(GoodsSaveRequest request, List<SpecSaveRequest> specs, List<SkuSaveRequest> skus) {
        return new GoodsSaveRequest(request.categoryId(), request.goodsName(), request.goodsSubtitle(),
                request.mainImage(), request.detailContent(), request.freightTemplateId(),
                request.sortOrder(), request.status(), request.images(), specs, skus);
    }

    private Long createGoods(GoodsSaveRequest request) {
        Long id = inTenant(() -> goodsService.create(request));
        createdGoodsIds.add(id);
        return id;
    }

    private MallGoods goods(Long goodsId) {
        return inTenant(() -> goodsRepository.findById(goodsId).orElseThrow());
    }

    private List<MallSku> skusOf(Long goodsId) {
        return inTenant(() -> skuRepository.findByGoodsIdOrderByIdAsc(goodsId));
    }

    // ================================================================ 商品新增

    @Test
    @DisplayName("新增商品:汇总字段按启用的 SKU 重算,状态缺省为下架")
    void createComputesSummaryFromEnabledSkus() {
        Long categoryId = createCategory("汇总分类" + suffix());
        Long goodsId = createGoods(goodsRequest(categoryId, "汇总商品" + suffix(), null,
                List.of(sku("SUM-A-" + suffix(), "20.00", 5), sku("SUM-B-" + suffix(), "10.00", 3))));

        MallGoods saved = goods(goodsId);
        assertThat(saved.getSalePriceMin()).as("取最低价").isEqualByComparingTo("10.00");
        assertThat(saved.getSalePriceMax()).isEqualByComparingTo("20.00");
        assertThat(saved.getTotalStock()).as("可售库存之和").isEqualTo(8);
        assertThat(saved.getStatus()).as("不传状态时新建商品默认下架,避免半成品直接开卖").isZero();
        assertThat(saved.getSaleCount()).isZero();

        List<MallSku> skus = skusOf(goodsId);
        assertThat(skus).hasSize(2);
        assertThat(skus).allMatch(item -> item.getStatus() == 1, "SKU 不传状态时默认启用");
        assertThat(skus).allMatch(item -> item.getLockedStock() == 0);
    }

    @Test
    @DisplayName("新增商品:轮播图跳过空白项,规格与 SKU 的规格值关联都要建起来")
    void createStoresImagesSpecsAndSkuLinks() {
        Long categoryId = createCategory("规格分类" + suffix());
        String skuCode = "SPEC-" + suffix();
        List<SpecSaveRequest> specs = List.of(new SpecSaveRequest("颜色", List.of("红", "蓝")));
        Long goodsId = createGoods(withSpecs(
                new GoodsSaveRequest(categoryId, "规格商品" + suffix(), null, "https://example.com/main.png",
                        null, null, 1, 1, List.of("https://example.com/1.png", "  ", "https://example.com/2.png"),
                        null, null),
                specs, List.of(new SkuSaveRequest(null, skuCode, "红色", null, new BigDecimal("9.90"), null, 2, null, null,
                        List.of("红")))));

        inTenant(() -> {
            assertThat(goodsImageRepository.findByGoodsIdOrderBySortOrderAscIdAsc(goodsId))
                    .as("空白地址要被跳过")
                    .hasSize(2);

            List<MallGoodsSpec> savedSpecs = goodsSpecRepository.findByGoodsIdOrderBySortOrderAscIdAsc(goodsId);
            assertThat(savedSpecs).hasSize(1);
            List<MallGoodsSpecValue> values =
                    goodsSpecValueRepository.findBySpecIdInOrderBySortOrderAscIdAsc(List.of(savedSpecs.get(0).getId()));
            assertThat(values).extracting(MallGoodsSpecValue::getSpecValue).containsExactly("红", "蓝");

            Long skuId = skuRepository.findByGoodsIdOrderByIdAsc(goodsId).get(0).getId();
            assertThat(skuSpecValueRepository.findBySkuIdIn(List.of(skuId)))
                    .as("SKU 与规格值的关联不建,端上这个规格组合就选不了")
                    .hasSize(1);
            return null;
        });

        // 详情要能把规格值名称带回来(端上显示"红"而不是一个 id)
        GoodsDetailView detail = inTenant(() -> goodsService.detail(goodsId));
        assertThat(detail.specs()).hasSize(1);
        assertThat(detail.skus().get(0).specValues()).containsExactly("红");
    }

    @Test
    @DisplayName("新增商品:分类与运费模板都必须存在")
    void createValidatesReferences() {
        assertThatThrownBy(() -> inTenant(() -> goodsService.create(goodsRequest(999999L, "无分类商品" + suffix(), 1,
                List.of(sku("NC-" + suffix(), "1.00", 1))))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("商品分类不存在");

        Long categoryId = createCategory("运费校验分类" + suffix());
        GoodsSaveRequest withBadTemplate = new GoodsSaveRequest(categoryId, "无模板商品" + suffix(), null,
                "https://example.com/main.png", null, 999999L, 1, 1, null, null,
                List.of(sku("NT-" + suffix(), "1.00", 1)));
        assertThatThrownBy(() -> inTenant(() -> goodsService.create(withBadTemplate)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("运费模板不存在");
    }

    @Test
    @DisplayName("新增商品:SKU 编码在本次提交内不能重复,也不能占用别人的编码")
    void createValidatesSkuCodes() {
        Long categoryId = createCategory("编码分类" + suffix());
        String duplicated = "DUP-" + suffix();
        assertThatThrownBy(() -> inTenant(() -> goodsService.create(goodsRequest(categoryId, "重复编码" + suffix(), 1,
                List.of(sku(duplicated, "1.00", 1), sku(duplicated, "2.00", 1))))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("本次提交里 SKU 编码重复");

        String occupied = "OCC-" + suffix();
        createGoods(goodsRequest(categoryId, "先占编码" + suffix(), 1, List.of(sku(occupied, "1.00", 1))));
        assertThatThrownBy(() -> inTenant(() -> goodsService.create(goodsRequest(categoryId, "抢占编码" + suffix(), 1,
                List.of(sku(occupied, "1.00", 1))))))
                .as("库上有 uk_tenant_sku_code,这里要提前给出可读的错误而不是让它撞库")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SKU 编码已被其他商品使用");
    }

    @Test
    @DisplayName("新增商品:规格值不能为空、不能在同一规格内重复、也不能跨规格重复")
    void createValidatesSpecValues() {
        Long categoryId = createCategory("规格校验分类" + suffix());

        assertThatThrownBy(() -> inTenant(() -> goodsService.create(new GoodsSaveRequest(
                categoryId, "空规格值" + suffix(), null, "https://example.com/main.png", null, null, 1, 1, null,
                List.of(new SpecSaveRequest("颜色", List.of("  ", ""))),
                List.of(sku("EV-" + suffix(), "1.00", 1))))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少需要一个规格值");

        assertThatThrownBy(() -> inTenant(() -> goodsService.create(new GoodsSaveRequest(
                categoryId, "规格内重复" + suffix(), null, "https://example.com/main.png", null, null, 1, 1, null,
                List.of(new SpecSaveRequest("颜色", List.of("红", " 红 "))),
                List.of(sku("EV2-" + suffix(), "1.00", 1))))))
                .as("trim 之后相同也算重复")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存在重复的规格值");

        assertThatThrownBy(() -> inTenant(() -> goodsService.create(new GoodsSaveRequest(
                categoryId, "跨规格重复" + suffix(), null, "https://example.com/main.png", null, null, 1, 1, null,
                List.of(new SpecSaveRequest("颜色", List.of("标准")), new SpecSaveRequest("尺寸", List.of("标准"))),
                List.of(sku("EV3-" + suffix(), "1.00", 1))))))
                .as("规格值名称是 SKU 关联的键,重名会让 SKU 无法区分自己选了哪个")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("在多个规格下重复");
    }

    @Test
    @DisplayName("新增商品:SKU 引用了没提交的规格值时拒绝(否则端上这个组合选不了)")
    void createRejectsSkuSpecValueOutsideSubmission() {
        Long categoryId = createCategory("越界规格分类" + suffix());

        assertThatThrownBy(() -> inTenant(() -> goodsService.create(new GoodsSaveRequest(
                categoryId, "越界规格" + suffix(), null, "https://example.com/main.png", null, null, 1, 1, null,
                List.of(new SpecSaveRequest("颜色", List.of("红"))),
                List.of(new SkuSaveRequest(null, "OS-" + suffix(), "蓝色", null, new BigDecimal("1.00"), null,
                        1, null, null, List.of("蓝")))))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不在本次提交的规格里");
    }

    // ================================================================ 商品修改

    @Test
    @DisplayName("修改商品:本次没提交的旧 SKU 停售而不是删除,汇总只看启用的 SKU")
    void updateStopsUnsubmittedSkus() {
        Long categoryId = createCategory("停售分类" + suffix());
        String keepCode = "KEEP-" + suffix();
        Long goodsId = createGoods(goodsRequest(categoryId, "停售商品" + suffix(), 1,
                List.of(sku(keepCode, "10.00", 2), sku("DROP-" + suffix(), "5.00", 9))));
        List<MallSku> before = skusOf(goodsId);
        Long keepSkuId = before.stream().filter(item -> keepCode.equals(item.getSkuCode())).findFirst()
                .orElseThrow().getId();

        inTenant(() -> {
            goodsService.update(goodsId, goodsRequest(categoryId, "停售商品" + suffix(), 1,
                    List.of(new SkuSaveRequest(keepSkuId, keepCode, "保留规格", null, new BigDecimal("10.00"), null,
                            2, null, null, List.of()))));
            return null;
        });

        List<MallSku> after = skusOf(goodsId);
        assertThat(after).as("旧 SKU 必须还在:历史订单与售后要回查到它").hasSize(2);
        MallSku dropped = after.stream().filter(item -> item.getId().equals(keepSkuId)).findFirst().orElseThrow();
        MallSku stopped = after.stream().filter(item -> !item.getId().equals(keepSkuId)).findFirst().orElseThrow();
        assertThat(dropped.getStatus()).isEqualTo(1);
        assertThat(stopped.getStatus()).as("未提交的旧 SKU 置为停售").isZero();
        assertThat(goods(goodsId).getTotalStock()).as("停售的 9 件不计入可售库存").isEqualTo(2);
        assertThat(goods(goodsId).getSalePriceMax()).as("停售的低价也不影响价格区间").isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("修改商品:全部 SKU 都被停售时,汇总回落为 0 而不是保留旧值")
    void updateResetsSummaryWhenNoEnabledSku() {
        Long categoryId = createCategory("清空汇总分类" + suffix());
        Long goodsId = createGoods(goodsRequest(categoryId, "清空汇总" + suffix(), 1,
                List.of(sku("ONLY-" + suffix(), "66.00", 4))));
        assertThat(goods(goodsId).getTotalStock()).isEqualTo(4);

        // 提交一份不含任何 SKU 的更新:现存 SKU 全部转为停售。
        // (控制器上 @NotEmpty 会先拦住空列表,但服务层仍要自己站得住 —— 定时任务与脚本会直接调它)
        inTenant(() -> {
            goodsService.update(goodsId, new GoodsSaveRequest(categoryId, "清空汇总" + suffix(), null,
                    "https://example.com/main.png", null, null, 1, 1, null, null, List.of()));
            return null;
        });

        MallGoods saved = goods(goodsId);
        assertThat(skusOf(goodsId)).allMatch(item -> item.getStatus() == 0, "全部转停售");
        assertThat(saved.getSalePriceMin()).as("没有启用的 SKU 时价格区间回落为 0,而不是留着旧的 66").isEqualByComparingTo("0");
        assertThat(saved.getSalePriceMax()).isEqualByComparingTo("0");
        assertThat(saved.getTotalStock()).isZero();
    }

    @Test
    @DisplayName("修改商品:传入不属于该商品的 SKU ID 被拒(前端串了数据或越权构造)")
    void updateRejectsForeignSkuId() {
        Long categoryId = createCategory("越权SKU分类" + suffix());
        Long goodsId = createGoods(goodsRequest(categoryId, "越权SKU" + suffix(), 1,
                List.of(sku("OWN-" + suffix(), "1.00", 1))));
        Long otherGoodsId = createGoods(goodsRequest(categoryId, "别人的商品" + suffix(), 1,
                List.of(sku("OTHER-" + suffix(), "1.00", 1))));
        Long otherSkuId = skusOf(otherGoodsId).get(0).getId();

        assertThatThrownBy(() -> inTenant(() -> {
            goodsService.update(goodsId, goodsRequest(
                    categoryId, "越权SKU" + suffix(), 1,
                    List.of(new SkuSaveRequest(otherSkuId, "X-" + suffix(), "偷来的规格", null,
                            new BigDecimal("1.00"), null, 1, null, null, List.of()))));
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SKU 不属于该商品");
    }

    @Test
    @DisplayName("修改商品:手工改库存要写一条类型为 5 的流水,没变则不写")
    void updateRecordsManualStockChange() {
        Long categoryId = createCategory("库存流水分类" + suffix());
        String code = "LOG-" + suffix();
        Long goodsId = createGoods(goodsRequest(categoryId, "库存流水" + suffix(), 1, List.of(sku(code, "3.00", 10))));
        Long skuId = skusOf(goodsId).get(0).getId();

        inTenant(() -> {
            goodsService.update(goodsId, goodsRequest(categoryId, "库存流水" + suffix(), 1,
                    List.of(new SkuSaveRequest(skuId, code, "规格" + code, null, new BigDecimal("3.00"), null,
                            4, null, null, List.of()))));
            return null;
        });

        List<MallStockLog> logs = inTenant(() -> stockLogRepository.findAll().stream()
                .filter(log -> skuId.equals(log.getSkuId()))
                .toList());
        assertThat(logs).as("库存是钱,任何变动都要留痕").hasSize(1);
        assertThat(logs.get(0).getChangeType()).isEqualTo(5);
        assertThat(logs.get(0).getChangeStock()).as("10 → 4 的差值是 -6").isEqualTo(-6);

        // 原样再提交一次:库存没变,不该再多一条流水
        inTenant(() -> {
            goodsService.update(goodsId, goodsRequest(categoryId, "库存流水" + suffix(), 1,
                    List.of(new SkuSaveRequest(skuId, code, "规格" + code, null, new BigDecimal("3.00"), null,
                            4, null, null, List.of()))));
            return null;
        });
        assertThat(inTenant(() -> stockLogRepository.findAll().stream()
                .filter(log -> skuId.equals(log.getSkuId())).count()))
                .as("没有变化就不该留痕,否则流水里全是噪音")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("修改商品:轮播图与规格是全量替换,旧规格值关联要一并清掉")
    void updateReplacesImagesAndSpecs() {
        Long categoryId = createCategory("全量替换分类" + suffix());
        String code = "REP-" + suffix();
        Long goodsId = createGoods(new GoodsSaveRequest(categoryId, "全量替换" + suffix(), null,
                "https://example.com/main.png", null, null, 1, 1,
                List.of("https://example.com/old.png"),
                List.of(new SpecSaveRequest("颜色", List.of("红", "蓝"))),
                List.of(new SkuSaveRequest(null, code, "红色", null, new BigDecimal("1.00"), null, 1, null, null,
                        List.of("红")))));
        Long skuId = skusOf(goodsId).get(0).getId();

        inTenant(() -> {
            goodsService.update(goodsId, new GoodsSaveRequest(categoryId, "全量替换" + suffix(), null,
                    "https://example.com/main.png", null, null, 1, 1,
                    List.of("https://example.com/new.png"),
                    List.of(new SpecSaveRequest("尺寸", List.of("S"))),
                    List.of(new SkuSaveRequest(skuId, code, "S 码", null, new BigDecimal("1.00"), null, 1, null, null,
                            List.of("S")))));
            return null;
        });

        inTenant(() -> {
            assertThat(goodsImageRepository.findByGoodsIdOrderBySortOrderAscIdAsc(goodsId))
                    .extracting(image -> image.getImageUrl())
                    .containsExactly("https://example.com/new.png");

            List<MallGoodsSpec> specs = goodsSpecRepository.findByGoodsIdOrderBySortOrderAscIdAsc(goodsId);
            assertThat(specs).extracting(MallGoodsSpec::getSpecName).containsExactly("尺寸");

            // 旧关联指向的规格值已经被删了,不清掉就成了悬空引用
            List<Long> skuIds = List.of(skuId);
            assertThat(skuSpecValueRepository.findBySkuIdIn(skuIds)).hasSize(1);
            Long linkedValueId = skuSpecValueRepository.findBySkuIdIn(skuIds).get(0).getSpecValueId();
            assertThat(goodsSpecValueRepository.findById(linkedValueId).orElseThrow().getSpecValue()).isEqualTo("S");
            return null;
        });
    }

    // ================================================================ 上下架与删除

    @Test
    @DisplayName("上下架:状态值必须合法,库存为 0 不允许上架")
    void changeStatusValidates() {
        Long categoryId = createCategory("上下架分类" + suffix());
        Long goodsId = createGoods(goodsRequest(categoryId, "上下架" + suffix(), 0, List.of(sku("ST-" + suffix(), "1.00", 0))));

        assertThatThrownBy(() -> inTenant(() -> {
            goodsService.changeStatus(goodsId, 5);
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("只能是 0(下架)或 1(上架)");

        assertThatThrownBy(() -> inTenant(() -> {
            goodsService.changeStatus(goodsId, 1);
            return null;
        }))
                .as("上架一个没库存的商品,用户点进去就是卖不了")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存为 0,无法上架");

        // 补上库存后可以上架
        Long skuId = skusOf(goodsId).get(0).getId();
        inTenant(() -> {
            goodsService.update(goodsId, goodsRequest(categoryId, "上下架" + suffix(), 0, List.of(
                    new SkuSaveRequest(skuId, "ST-" + suffix(), "规格", null, new BigDecimal("1.00"), null,
                            1, null, null, List.of()))));
            return null;
        });
        inTenant(() -> {
            goodsService.changeStatus(goodsId, 1);
            return null;
        });
        assertThat(goods(goodsId).getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("删除商品:已有订单记录时只能下架,不能删除")
    void deleteRejectsGoodsWithOrderItems() {
        Long categoryId = createCategory("订单引用分类" + suffix());
        Long goodsId = createGoods(goodsRequest(categoryId, "被下单的商品" + suffix(), 1,
                List.of(sku("ORD-" + suffix(), "1.00", 5))));
        Long skuId = skusOf(goodsId).get(0).getId();
        asClient(customerId, () -> orderService.create(
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(skuId, 1)), addressId, null, null)));

        assertThatThrownBy(() -> inTenant(() -> {
            goodsService.delete(goodsId);
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DATA_CONFLICT))
                .hasMessageContaining("只能下架不能删除");
    }

    @Test
    @DisplayName("删除商品:级联清掉图片、规格、规格值、SKU 与规格关联")
    void deleteCascadesOwnedRows() {
        Long categoryId = createCategory("级联删除分类" + suffix());
        String code = "DEL-" + suffix();
        Long goodsId = createGoods(new GoodsSaveRequest(categoryId, "级联删除" + suffix(), null,
                "https://example.com/main.png", null, null, 1, 1,
                List.of("https://example.com/1.png"),
                List.of(new SpecSaveRequest("颜色", List.of("红"))),
                List.of(new SkuSaveRequest(null, code, "红色", null, new BigDecimal("1.00"), null, 1, null, null,
                        List.of("红")))));
        Long skuId = skusOf(goodsId).get(0).getId();
        Long specId = inTenant(() -> goodsSpecRepository.findByGoodsIdOrderBySortOrderAscIdAsc(goodsId).get(0).getId());

        inTenant(() -> {
            goodsService.delete(goodsId);
            return null;
        });
        createdGoodsIds.remove(goodsId);

        inTenant(() -> {
            assertThat(goodsRepository.findById(goodsId)).isEmpty();
            assertThat(skuRepository.findById(skuId)).isEmpty();
            assertThat(skuSpecValueRepository.findBySkuIdIn(List.of(skuId))).isEmpty();
            assertThat(goodsSpecRepository.findById(specId)).isEmpty();
            assertThat(goodsSpecValueRepository.findBySpecIdInOrderBySortOrderAscIdAsc(List.of(specId))).isEmpty();
            assertThat(goodsImageRepository.findByGoodsIdOrderBySortOrderAscIdAsc(goodsId)).isEmpty();
            return null;
        });
    }

    // ================================================================ 商品列表

    @Test
    @DisplayName("商品列表:按名称/分类/状态过滤,并带上分类名")
    void pageFiltersAndCarriesCategoryName() {
        Long categoryId = createCategory("列表分类" + suffix());
        String name = "列表商品" + suffix();
        Long goodsId = createGoods(goodsRequest(categoryId, name, 1, List.of(sku("PAGE-" + suffix(), "8.00", 2))));

        PageResult<GoodsView> byName = inTenant(() -> goodsService.page(name, null, null, 1, 10));
        assertThat(byName.list()).extracting(GoodsView::id).containsExactly(goodsId);
        assertThat(byName.list().get(0).categoryName()).as("列表要带分类名,否则前端只能显示一个 id").isNotBlank();

        assertThat(inTenant(() -> goodsService.page(name, categoryId, null, 1, 10)).total()).isEqualTo(1);
        assertThat(inTenant(() -> goodsService.page(name, null, 0, 1, 10)).total())
                .as("该商品是上架状态,按已下架过滤时不该出现")
                .isZero();
        assertThat(inTenant(() -> goodsService.page("不存在的商品名" + suffix(), null, null, 1, 10)).total()).isZero();
    }

    // ================================================================ 分类

    @Test
    @DisplayName("分类新增:同级同名拒绝、上级不存在拒绝、最多两级")
    void categoryCreateValidates() {
        String name = "一级分类" + suffix();
        Long rootId = createCategory(name);

        assertThatThrownBy(() -> inTenant(() -> categoryService.create(
                new CategorySaveRequest(null, name, null, null, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同级下已存在同名分类");

        assertThatThrownBy(() -> inTenant(() -> categoryService.create(
                new CategorySaveRequest(999999L, "孤儿分类" + suffix(), null, null, 1))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上级分类不存在");

        Long childId = createCategory(rootId, "二级分类" + suffix());
        assertThatThrownBy(() -> inTenant(() -> categoryService.create(
                new CategorySaveRequest(childId, "三级分类" + suffix(), null, null, 1))))
                .as("分类只允许两级:三级分类在端上没法导航")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多两级");
    }

    @Test
    @DisplayName("分类修改:不能把自己设为自己的上级;同名判断要排除自己")
    void categoryUpdateValidates() {
        String name = "改名分类" + suffix();
        Long categoryId = createCategory(name);

        assertThatThrownBy(() -> inTenant(() -> {
            categoryService.update(categoryId, new CategorySaveRequest(categoryId, name, null, null, 1));
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上级分类不能是自己");

        // 同名提交给自己:不算冲突,且只传名字时不动排序与状态
        inTenant(() -> {
            categoryService.update(categoryId, new CategorySaveRequest(null, name, "https://example.com/i.png", null, null));
            return null;
        });
        inTenant(() -> {
            MallGoodsCategory category = categoryRepository.findById(categoryId).orElseThrow();
            assertThat(category.getCategoryName()).isEqualTo(name);
            assertThat(category.getIcon()).isEqualTo("https://example.com/i.png");
            assertThat(category.getSortOrder()).as("null 表示不改").isZero();
            assertThat(category.getStatus()).isEqualTo(1);
            return null;
        });
    }

    @Test
    @DisplayName("分类删除:有下级或有商品都要拒绝,都没有才删得掉")
    void categoryDeleteValidates() {
        Long parentId = createCategory("待删分类" + suffix());
        Long childId = createCategory(parentId, "待删子分类" + suffix());

        assertThatThrownBy(() -> inTenant(() -> {
            categoryService.delete(parentId);
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存在下级分类");

        // 子分类下有商品时同样拒绝
        createGoods(goodsRequest(childId, "占用分类的商品" + suffix(), 0, List.of(sku("CAT-" + suffix(), "1.00", 1))));
        assertThatThrownBy(() -> inTenant(() -> {
            categoryService.delete(childId);
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先调整商品分类");

        // 先删商品,再删分类即可
        Long goodsId = createdGoodsIds.get(createdGoodsIds.size() - 1);
        inTenant(() -> {
            goodsService.delete(goodsId);
            return null;
        });
        createdGoodsIds.remove(goodsId);
        inTenant(() -> {
            categoryService.delete(childId);
            categoryService.delete(parentId);
            return null;
        });
        createdCategoryIds.remove(childId);
        createdCategoryIds.remove(parentId);
        inTenant(() -> {
            assertThat(categoryRepository.findById(childId)).isEmpty();
            assertThat(categoryRepository.findById(parentId)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("分类树:按状态过滤并保持父子嵌套")
    void categoryTreeFiltersAndNests() {
        Long parentId = createCategory("树分类" + suffix());
        Long childId = createCategory(parentId, "树子分类" + suffix());
        Long disabled = inTenant(() -> categoryService.create(
                new CategorySaveRequest(null, "停用分类" + suffix(), null, null, 0)));
        createdCategoryIds.add(disabled);

        List<CategoryTreeNode> enabled = inTenant(() -> categoryService.tree(1));
        CategoryTreeNode parent = enabled.stream().filter(node -> node.id().equals(parentId)).findFirst().orElseThrow();
        assertThat(parent.children()).extracting(CategoryTreeNode::id).contains(childId);
        assertThat(enabled).extracting(CategoryTreeNode::id)
                .as("停用的分类不出现在启用树里")
                .doesNotContain(disabled);

        List<CategoryTreeNode> disabledOnly = inTenant(() -> categoryService.tree(0));
        assertThat(disabledOnly).extracting(CategoryTreeNode::id).contains(disabled).doesNotContain(parentId);
    }
}
