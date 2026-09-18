package com.minimall.db.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移脚本的约定检查(架构文档 9.5、9.6)。
 *
 * <p>纯文件检查,不连数据库,所以放在默认测试集里(每次构建都跑)。它替代的是 9.6 里那条
 * "CI 的迁移脚本健康检查 job"中最容易自动化的一部分:**命名与版本顺序**;
 * 脚本能不能真正执行、有没有失败记录,由 {@code FlywayHealthIntegrationTest} 在真实库上验证。
 *
 * <p>为什么值得单独立一个用例:这两个约定都不是靠"大家记得"能守住的 ——
 * 命名写错(比如漏了下划线、用了大写)时 Flyway 会**静默忽略**该文件,不报错、不执行,
 * 表现为"脚本提交了但表没建",排查方向很容易跑偏。
 */
class MigrationScriptConventionTest {

    private static final Path MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    /** Flyway 默认命名约定:{@code V{版本}__{描述}.sql};描述用小写下划线,与 V1/V2 的既有风格一致。 */
    private static final Pattern FILE_NAME = Pattern.compile("^V(\\d+)__([a-z0-9_]+)\\.sql$");

    @Test
    @DisplayName("迁移脚本命名符合 V{n}__{描述}.sql,版本号不重复且与文件名顺序一致")
    void scriptNamesFollowFlywayConvention() throws IOException {
        List<String> fileNames = sqlFileNames();
        assertThat(fileNames).as("迁移目录里没有脚本:空库无法建起来").isNotEmpty();

        List<Integer> versions = new ArrayList<>();
        for (String name : fileNames) {
            Matcher matcher = FILE_NAME.matcher(name);
            assertThat(matcher.matches())
                    .as("脚本名不符合 V{版本}__{描述}.sql:%s —— 不符合约定的文件会被 Flyway 静默忽略", name)
                    .isTrue();
            versions.add(Integer.parseInt(matcher.group(1)));
        }

        assertThat(versions).as("版本号重复:Flyway 会直接报错,但如果文件名大小写/下划线不同,重复会更隐蔽")
                .doesNotHaveDuplicates();
        assertThat(versions)
                .as("按文件名排序后版本号应当递增——乱序会让 review 时看不出执行顺序,也容易撞版本号")
                .isSorted();
        assertThat(versions.get(0)).as("版本号从 1 开始(基线脚本 V1 是建表脚本)").isEqualTo(1);
    }

    private List<String> sqlFileNames() throws IOException {
        try (var paths = Files.list(MIGRATION_DIR)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }
}
