package com.minimall.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 菜单与前端页面的对应关系(前端文档 5.2)。
 *
 * <p><b>为什么需要它</b>:"菜单指向哪个页面"是**跨模块的隐式约定** —— 后端迁移里写
 * {@code route_path},前端按它去 {@code src/views} 下找同名目录。约定的两端在两个仓库目录里,
 * 没有任何编译期检查。而菜单现在是后端驱动的(7.4),所以"迁移里加了菜单但页面还没写"的表现是:
 * 菜单出现、点进去白屏或 404,而且只有运营点到那一刻才会发现 —— 微信支付配置菜单就曾这样挂了一个版本。
 *
 * <p>路径拼法与前端 {@code resolveMenuPath} 保持一致:目录给全路径(/system),页面给父级下的片段(user)。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class MenuViewContractTest {

    /** 前端仓库目录。api 模块的工作目录是 api/,所以页面向上一层找。 */
    private static final Path VIEW_ROOT = Path.of("..", "web", "src", "views");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("每个页面菜单的 route_path 都能在前端找到对应页面")
    void everyPageMenuHasAView() {
        assumeTrue(Files.isDirectory(VIEW_ROOT),
                "找不到前端目录(可能在只检出 api 模块的环境里跑),跳过:" + VIEW_ROOT.toAbsolutePath());

        Map<Long, MenuRow> menus = allMenus();
        List<MenuRow> pages = menus.values().stream().filter(row -> row.menuType == 2).toList();
        assertThat(pages).as("菜单表里应当有页面菜单,否则这条用例没有意义").isNotEmpty();

        List<String> missing = pages.stream()
                .map(page -> Map.entry(page, resolvePath(page, menus)))
                .filter(entry -> !Files.isRegularFile(viewFile(entry.getValue())))
                .map(entry -> "%s(拼出 %s)-> 缺 %s".formatted(
                        entry.getKey().menuName(), entry.getValue(), viewFile(entry.getValue())))
                .toList();

        assertThat(missing)
                .as("""
                        菜单指向了不存在的页面。菜单现在是后端驱动的,运营点进去只会看到 404,
                        而迁移脚本本身不会报任何错。要么补页面,要么把菜单的 route_path 改对。""")
                .isEmpty();
    }

    /** route_path 到视图文件的映射约定(前端文档 5.2):/system/user -> views/system/user/index.vue。 */
    private Path viewFile(String fullPath) {
        return VIEW_ROOT.resolve(fullPath.substring(1) + "/index.vue");
    }

    private Map<Long, MenuRow> allMenus() {
        Map<Long, MenuRow> menus = new HashMap<>();
        jdbcTemplate.query("select id, parent_id, menu_name, menu_type, route_path from sys_menu",
                rs -> {
                    long id = rs.getLong("id");
                    menus.put(id, new MenuRow(rs.getLong("parent_id"), rs.getString("menu_name"),
                            rs.getInt("menu_type"), rs.getString("route_path")));
                });
        return menus;
    }

    /** 与前端 {@code resolveMenuPath} 同一套规则:以 / 开头的直接当全路径,否则拼到父路径后面。 */
    private String resolvePath(MenuRow menu, Map<Long, MenuRow> menus) {
        String segment = menu.routePath == null ? "" : menu.routePath;
        if (segment.startsWith("/")) {
            return segment;
        }
        MenuRow parent = menus.get(menu.parentId);
        String parentPath = parent == null ? "" : resolvePath(parent, menus);
        return parentPath + "/" + segment;
    }

    private record MenuRow(long parentId, String menuName, int menuType, String routePath) {
    }
}
