package com.minimall.infra.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CSV 生成的格式细节(架构文档 7.6)。纯函数,不需要 Spring。
 *
 * <p>这些细节单看都不起眼,错一个的后果却是"导出的文件打不开或数据错位",而且没人会去查:
 * BOM 缺了中文乱码、转义漏了列会串、公式前缀不加则打开文件就执行内容。
 */
@Tag("unit")
class CsvExporterTest {

    @Test
    @DisplayName("带 UTF-8 BOM:不加的话 Excel 按本地编码解析,中文全是乱码")
    void startsWithBom() {
        byte[] csv = CsvExporter.toCsv(List.of("模块"), List.of(List.of("租户管理")));

        assertThat(csv[0] & 0xFF).isEqualTo(0xEF);
        assertThat(csv[1] & 0xFF).isEqualTo(0xBB);
        assertThat(csv[2] & 0xFF).isEqualTo(0xBF);
        assertThat(new String(csv, StandardCharsets.UTF_8)).startsWith("\uFEFF模块");
    }

    @Test
    @DisplayName("含逗号/引号/换行的值要加引号并翻倍内部引号,否则列会错位")
    void escapesSpecialCharacters() {
        byte[] csv = CsvExporter.toCsv(List.of("a", "b"),
                List.of(List.of("含,逗号", "含\"引号"), List.of("含\n换行", "普通")));

        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).contains("\"含,逗号\",\"含\"\"引号\"");
        assertThat(text).contains("\"含\n换行\",普通");
    }

    @Test
    @DisplayName("以 = + - @ 开头的值要加前缀:否则 Excel 会当公式执行(CSV 注入)")
    void neutralizesFormula() {
        byte[] csv = CsvExporter.toCsv(List.of("参数"),
                List.of(List.of("=cmd|'/c calc'!A1"), List.of("+1"), List.of("-2"), List.of("@x"), List.of("正常")));

        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).contains("'=cmd|'/c calc'!A1");
        assertThat(text).contains("'+1").contains("'-2").contains("'@x");
        assertThat(text).contains("\n正常").as("普通值不要动");
    }

    @Test
    @DisplayName("空值与 null 输出空单元格,不写 null 字面量")
    void writesEmptyForNull() {
        byte[] csv = CsvExporter.toCsv(List.of("a", "b"), List.of(java.util.Arrays.asList("有值", null)));

        assertThat(new String(csv, StandardCharsets.UTF_8)).contains("有值,\r\n");
        assertThat(new String(csv, StandardCharsets.UTF_8)).doesNotContain("null");
    }

    @Test
    @DisplayName("只有表头时也是一份合法 CSV(不能连表头都不给)")
    void writesHeaderWhenNoRows() {
        byte[] csv = CsvExporter.toCsv(List.of("时间", "模块"), List.of());

        assertThat(new String(csv, StandardCharsets.UTF_8)).isEqualTo("\uFEFF时间,模块\r\n");
    }
}
