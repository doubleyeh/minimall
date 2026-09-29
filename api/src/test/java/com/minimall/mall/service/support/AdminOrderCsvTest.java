package com.minimall.mall.service.support;

import com.minimall.infra.export.CsvExporter;
import com.minimall.mall.api.dto.AdminOrderView;
import com.minimall.mall.domain.MallOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单导出的列定义。
 *
 * <p>这里钉的是"文件本身对不对":列数与表头对齐(错位会让整张表串列)、金额是纯数字
 * (带了 ¥ 或千分位 Excel 就当文本,拿到文件的人没法求和)、空值落成空串而不是 null。
 */
class AdminOrderCsvTest {

    @Test
    @DisplayName("列数与表头一一对应")
    void cellsAlignWithHeaders() {
        assertThat(AdminOrderCsv.cells(fullView())).as("列数与表头不等就是整张表串列")
                .hasSize(AdminOrderCsv.HEADERS.size());
    }

    @Test
    @DisplayName("金额是纯数字,空值落成空串,状态给中文文案")
    void cellsAreReadable() {
        List<String> cells = AdminOrderCsv.cells(fullView());

        assertThat(cells).contains("NO-1", "待发货");
        assertThat(cells).as("金额不加货币符号与千分位,否则 Excel 当文本")
                .contains("12345.60", "8.00", "10.00", "5.00", "12332.60");
        assertThat(cells).doesNotContain("¥12345.60");

        List<String> blankCells = AdminOrderCsv.cells(new AdminOrderView(7L, "NO-2", 9L,
                MallOrder.STATUS_PENDING_PAY, new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("1.00"), "张三", "13800000000", "地址",
                null, null, null, null, LocalDateTime.of(2026, 1, 2, 3, 4, 5), null, null, null));
        assertThat(blankCells).as("没发货就没有物流信息,落成空串而不是 null").contains("");
        assertThat(blankCells).doesNotContainNull();
    }

    @Test
    @DisplayName("含逗号/引号的备注不会把列冲散,且带 BOM 与表头")
    void escapesTrickyValues() {
        AdminOrderView tricky = new AdminOrderView(7L, "NO-3", 9L, MallOrder.STATUS_FINISHED,
                new BigDecimal("1.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("1.00"), "张三", "13800000000", "广东省,深圳市",
                "要\"红色\"的,谢谢", null, null, null,
                LocalDateTime.of(2026, 1, 2, 3, 4, 5), null, null, null);

        String line = CsvExporter.line(AdminOrderCsv.cells(tricky));

        assertThat(line).as("逗号与引号都要被包起来并翻倍,否则列会错位")
                .contains("\"广东省,深圳市\"").contains("\"要\"\"红色\"\"的,谢谢\"");

        String csv = new String(CsvExporter.toCsv(AdminOrderCsv.HEADERS,
                List.of(AdminOrderCsv.cells(tricky))), StandardCharsets.UTF_8);
        assertThat(csv).as("缺 BOM 的话 Excel 打开中文是乱码").startsWith("\uFEFF");
        assertThat(csv).contains("订单号,状态,买家ID");
    }

    /** 一条各字段都填满的订单,便于断言"有值时输出什么"。 */
    private static AdminOrderView fullView() {
        return new AdminOrderView(7L, "NO-1", 9L, MallOrder.STATUS_PENDING_SHIP,
                new BigDecimal("12345.60"), new BigDecimal("8.00"), new BigDecimal("10.00"),
                new BigDecimal("5.00"), new BigDecimal("12332.60"),
                "张三", "13800000000", "广东省深圳市南山区测试路 1 号",
                "尽快发货", "顺丰", "SF1", 1,
                LocalDateTime.of(2026, 1, 2, 3, 4, 5), LocalDateTime.of(2026, 1, 2, 3, 5, 0), null, null);
    }
}
