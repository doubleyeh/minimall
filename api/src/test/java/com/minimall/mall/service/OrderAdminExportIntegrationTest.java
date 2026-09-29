package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.mall.api.dto.AdminOrderQuery;
import com.minimall.mall.api.dto.AdminOrderView;
import com.minimall.mall.api.dto.OrderCreateResponse;
import com.minimall.mall.domain.MallOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理端订单导出的取数(商城设计文档 3.4)。
 *
 * <p>导出本身只是把列表的取数结果拼成 CSV,风险集中在取数这一段:时间范围没生效会导出一整库,
 * 超上限不报错会导出一份"看起来完整"的半份数据。两件事从文件上都看不出来。
 */
class OrderAdminExportIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private OrderAdminService orderAdminService;

    @Test
    @DisplayName("取数:下单时间区间两端都含,区间外的订单不混进来")
    void exportFiltersByCreateTime() {
        OrderCreateResponse old = createOrder(customerId, addressId, 1);
        OrderCreateResponse recent = createOrder(customerId, addressId, 1);
        LocalDateTime oldTime = LocalDateTime.now().minusHours(2).withNano(0);
        backdate(old.orderId(), oldTime);

        // 起止取同一时刻:只有"含边界"才会命中这一笔
        List<AdminOrderView> exact = inTenant(() -> orderAdminService.listForExport(
                new AdminOrderQuery(null, null, oldTime, oldTime), 100));
        assertThat(exact).extracting(AdminOrderView::orderNo)
                .contains(old.orderNo())
                .doesNotContain(recent.orderNo());

        List<AdminOrderView> recentWindow = inTenant(() -> orderAdminService.listForExport(
                new AdminOrderQuery(null, null, oldTime.plusMinutes(30), LocalDateTime.now().plusHours(1)), 100));
        assertThat(recentWindow).extracting(AdminOrderView::orderNo)
                .contains(recent.orderNo())
                .doesNotContain(old.orderNo());
    }

    @Test
    @DisplayName("取数:状态过滤与列表共用同一份条件")
    void exportSharesFiltersWithList() {
        createOrder(customerId, addressId, 1);

        List<AdminOrderView> onlyPendingPay = inTenant(() -> orderAdminService.listForExport(
                new AdminOrderQuery(null, MallOrder.STATUS_PENDING_PAY, null, null), 100));

        assertThat(onlyPendingPay).isNotEmpty();
        assertThat(onlyPendingPay).allSatisfy(view ->
                assertThat(view.status()).isEqualTo(MallOrder.STATUS_PENDING_PAY));
    }

    @Test
    @DisplayName("取数:超过上限直接报错,不静默截断成半份数据")
    void exportRejectsOverLimit() {
        createOrder(customerId, addressId, 1);
        createOrder(customerId, addressId, 1);

        assertThatThrownBy(() -> inTenant(() -> orderAdminService.listForExport(
                new AdminOrderQuery(null, null, null, null), 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过 1 条");
    }

    /** 业务代码不允许改 create_time,只能直接改库(与"模拟时间流逝"的其它用例同一手法)。 */
    private void backdate(Long orderId, LocalDateTime createTime) {
        jdbcTemplate.update("update mall_order set create_time = ? where id = ?",
                Timestamp.valueOf(createTime), orderId);
    }
}
