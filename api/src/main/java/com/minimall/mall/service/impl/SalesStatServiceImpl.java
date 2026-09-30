package com.minimall.mall.service.impl;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.api.dto.SalesStatReport;
import com.minimall.mall.api.dto.SalesSummaryView;
import com.minimall.mall.api.dto.TopGoodsView;
import com.minimall.mall.domain.repository.MallOrderItemRepository;
import com.minimall.mall.domain.repository.MallOrderRepository;
import com.minimall.mall.domain.repository.MallWxRefundRepository;
import com.minimall.mall.service.SalesStatService;
import com.minimall.mall.service.support.OrderAmountCalculator;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售统计(商城设计文档 3.4)。
 *
 * <p>口径统一在这里:
 * <ul>
 *   <li><b>销售额</b>按**下单时间**落区间、且已支付({@code pay_time} 非空)的订单实付额 ——
 *       取消/退款过的也算,钱确实收过,退款单独在退款额里看</li>
 *   <li><b>退款额</b>按**退款成功时间**落区间 —— 与销售额不是同一批订单,所以"净额"只用于看量级</li>
 *   <li><b>商品排行</b>与销售额同一条时间轴(下单时间),按金额降序</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class SalesStatServiceImpl implements SalesStatService {

    /** 商品排行取前几名。固定值:再多的部分运营也不会看,而"可调"会让人以为要按业务调。 */
    private static final int TOP_GOODS_LIMIT = 10;

    private final MallOrderRepository orderRepository;
    private final MallWxRefundRepository refundRepository;
    private final MallOrderItemRepository orderItemRepository;
    private final OrderAmountCalculator calculator;

    public SalesStatServiceImpl(MallOrderRepository orderRepository, MallWxRefundRepository refundRepository,
                               MallOrderItemRepository orderItemRepository, OrderAmountCalculator calculator) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.orderItemRepository = orderItemRepository;
        this.calculator = calculator;
    }

    @Override
    public SalesStatReport report(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择统计的日期区间");
        }
        if (startTime.isAfter(endTime)) {
            // 不校验的话结果恒为空,看起来像"这段时间没卖出去东西"
            throw new BusinessException(ErrorCode.PARAM_INVALID, "开始时间不能晚于结束时间");
        }

        long orderCount = orderRepository.countByPayTimeIsNotNullAndCreateTimeBetween(startTime, endTime);
        BigDecimal paidAmount = zeroIfNull(orderRepository.sumPaidAmount(startTime, endTime));
        BigDecimal refundAmount = zeroIfNull(refundRepository.sumSucceededAmount(startTime, endTime));
        BigDecimal netAmount = calculator.money(paidAmount.subtract(refundAmount));
        // 客单价:没有订单时给 0,不做除法 —— 除零会抛异常把整个页面带下去
        BigDecimal avgOrderAmount = orderCount == 0 ? calculator.money(BigDecimal.ZERO)
                : calculator.money(paidAmount.divide(BigDecimal.valueOf(orderCount), 4, RoundingMode.HALF_UP));

        List<TopGoodsView> topGoods = orderItemRepository
                .findTopGoods(startTime, endTime, PageRequest.of(0, TOP_GOODS_LIMIT)).stream()
                .map(row -> new TopGoodsView(row.getGoodsId(), row.getGoodsName(),
                        row.getQuantity() == null ? 0L : row.getQuantity(),
                        calculator.money(row.getAmount())))
                .toList();

        return new SalesStatReport(
                new SalesSummaryView(orderCount, paidAmount, refundAmount, netAmount, avgOrderAmount), topGoods);
    }

    /** 聚合函数在没有匹配行时返回 null,而页面上应该显示 0。 */
    private BigDecimal zeroIfNull(BigDecimal value) {
        return calculator.money(value == null ? BigDecimal.ZERO : value);
    }
}
