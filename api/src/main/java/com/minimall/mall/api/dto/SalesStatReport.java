package com.minimall.mall.api.dto;

import java.util.List;

/**
 * 销售统计结果:日期区间内的汇总 + 商品排行。
 */
public record SalesStatReport(SalesSummaryView summary, List<TopGoodsView> topGoods) {
}
