package com.minimall.db.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移脚本健康检查(架构文档 9.6):在真实库上确认"代码里的脚本"与"库里应用过的版本"完全一致。
 *
 * <p>它对应 9.6 里那个 CI job 的落地形式 —— 用 `mvn test` 就能跑,不需要额外基础设施:
 * <ul>
 *   <li><b>没有待应用的脚本</b>:说明代码与目标库口径一致。CI 上应先 {@code flyway migrate}
 *       再跑测试;本地报这条,通常是"刚拉了新脚本但没重启应用"(启动时会自动迁移)</li>
 *   <li><b>没有失败记录</b>:Flyway 把失败的版本记成 {@code FAILED} 并中断后续迁移,
 *       正常状态下不该出现;真出现了按 9.6 的流程处理(先 {@code flyway repair} 清标记,再发新版本脚本前滚)</li>
 *   <li><b>已应用脚本的校验和与磁盘文件一致</b>:由 Flyway 自身保证(启动时 {@code validate} 会因为
 *       "已发布脚本被改动"直接失败),所以这里只断言状态,不重复实现校验和算法</li>
 * </ul>
 *
 * <p>依赖真实 MySQL,所以打 {@code integration} 标签(见 9.4)。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("dev")
class FlywayHealthIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("9.6:目标库的迁移状态健康——无待应用版本、无失败记录")
    void migrationsAreFullyApplied() {
        MigrationInfoService info = flyway.info();

        assertThat(info.current()).as("目标库一个版本都没应用过,确认连的是测试库而不是一个空库").isNotNull();

        assertThat(info.pending())
                .as("存在待应用的迁移脚本:代码里的脚本比库里新,CI 上应先 migrate 再跑测试")
                .isEmpty();

        assertThat(Arrays.stream(info.all())
                .filter(migration -> migration.getState() == MigrationState.FAILED)
                .toList())
                .as("存在失败的迁移版本:按 9.6 先 flyway repair 清标记,再用新版本脚本前滚")
                .isEmpty();
    }
}
