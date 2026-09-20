package com.minimall.sys.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.security.PermissionCacheService;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.sys.api.dto.MenuSaveRequest;
import com.minimall.sys.api.dto.MenuTreeNode;
import com.minimall.sys.domain.SysMenu;
import com.minimall.sys.domain.repository.SysMenuRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 菜单维护(架构文档 5.1、5.2、5.6)。
 *
 * <p>菜单是**平台级数据**:它不属于任何租户,却决定所有租户能看到什么。所以这里的规则里
 * 有三条"改错了会全平台出问题"的:
 * <ol>
 *   <li><b>权限码全局唯一</b> —— 不唯一会让 {@code @SaCheckPermission} 的语义失效:
 *       授权其中一个等于顺带授权另一个</li>
 *   <li><b>不能挂到自己的子孙下</b> —— 成环会让菜单树在渲染与遍历时无限递归</li>
 *   <li><b>删除要级联清理 sys_role_menu / sys_package_menu</b> —— 否则留下指向不存在菜单的
 *       授权记录,靠人查是查不出来的</li>
 * </ol>
 *
 * <p>用例自己造的菜单统一用 {@code it-} 前缀(route_path)或 {@code it:} 前缀(perm_code),
 * 清理时按前缀删 —— 这样即使某个用例中途失败,下一次运行也不会因为"权限码已被占用"而连锁失败。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("test")
class MenuServiceIntegrationTest {

    private static final long PLATFORM_TENANT_ID = 1L;
    private static final long SEED_ADMIN_ID = 1L;

    @Autowired
    private MenuService menuService;
    @Autowired
    private SysMenuRepository menuRepository;
    @Autowired
    private PermissionCacheService permissionCacheService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanResidue() {
        cleanItMenus();
    }

    @AfterEach
    void cleanUp() {
        cleanItMenus();
    }

    private void cleanItMenus() {
        jdbcTemplate.update("DELETE FROM sys_role_menu WHERE menu_id IN "
                + "(SELECT id FROM sys_menu WHERE perm_code LIKE 'it:%' OR route_path LIKE 'it-%')");
        jdbcTemplate.update("DELETE FROM sys_package_menu WHERE menu_id IN "
                + "(SELECT id FROM sys_menu WHERE perm_code LIKE 'it:%' OR route_path LIKE 'it-%')");
        jdbcTemplate.update("DELETE FROM sys_menu WHERE perm_code LIKE 'it:%' OR route_path LIKE 'it-%'");
    }

    /** 以平台超管身份执行(菜单维护只有平台侧能做)。 */
    private <T> T asPlatformAdmin(java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(PLATFORM_TENANT_ID, true, () -> {
            AuditContext.bind(new AuditContext(PLATFORM_TENANT_ID, SEED_ADMIN_ID, "127.0.0.1", "menu-it"));
            try {
                return action.get();
            } finally {
                AuditContext.clear();
            }
        });
    }

    private static MenuSaveRequest dir(String name) {
        return new MenuSaveRequest(0L, name, 1, "it-" + name, null, null, 1, 1, false);
    }

    private static MenuSaveRequest button(Long parentId, String name, String permCode) {
        return new MenuSaveRequest(parentId, name, 3, null, permCode, null, 1, 1, false);
    }

    private Long createMenu(MenuSaveRequest request) {
        return asPlatformAdmin(() -> menuService.create(request));
    }

    // ---------------------------------------------------------------- 新增

    @Test
    @DisplayName("新增目录:默认值补齐(排序 0、状态 1、非平台菜单)")
    void createAppliesDefaults() {
        Long menuId = createMenu(new MenuSaveRequest(0L, "it默认值目录", 1, "it-defaults", null, null, null, null, null));

        asPlatformAdmin(() -> {
            SysMenu menu = menuRepository.findById(menuId).orElseThrow();
            assertThat(menu.getParentId()).isZero();
            assertThat(menu.getSortOrder()).isZero();
            assertThat(menu.getStatus()).as("不传状态时默认启用").isEqualTo(1);
            assertThat(menu.getIsPlatform()).as("不显式声明就不是平台菜单").isZero();
            return null;
        });
    }

    @Test
    @DisplayName("按钮菜单必须有权限码(否则这个按钮永远不会被鉴权控制)")
    void createButtonRequiresPermCode() {
        assertThatThrownBy(() -> asPlatformAdmin(() ->
                menuService.create(new MenuSaveRequest(0L, "it无权限码按钮", 3, null, "  ", null, 1, 1, false))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("按钮级菜单必须填写权限标识");
    }

    @Test
    @DisplayName("权限码重复被拒(唯一性决定鉴权语义)")
    void createRejectsDuplicatePermCode() {
        createMenu(button(0L, "it按钮A", "it:dup:code"));

        assertThatThrownBy(() -> createMenu(button(0L, "it按钮B", "it:dup:code")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DATA_CONFLICT));
    }

    @Test
    @DisplayName("上级菜单不存在:NOT_FOUND(而不是静默挂在 0 下)")
    void createRejectsUnknownParent() {
        assertThatThrownBy(() -> createMenu(button(999999L, "it孤儿按钮", "it:orphan")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------------------------------------------------------------- 修改

    @Test
    @DisplayName("改权限码:失效全部租户的权限缓存(不失效等于老权限还能继续用)")
    void permCodeChangeInvalidatesAllTenants() {
        Long menuId = createMenu(button(0L, "it缓存按钮", "it:cache:old"));
        long before = permissionCacheService.currentVersion(PLATFORM_TENANT_ID);

        asPlatformAdmin(() -> {
            menuService.update(menuId, button(0L, "it缓存按钮", "it:cache:new"));
            return null;
        });

        assertThat(permissionCacheService.currentVersion(PLATFORM_TENANT_ID))
                .as("权限码变了必须 INCR 版本号")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("只改名称:不失效缓存(避免无意义的全租户缓存穿透)")
    void nameOnlyChangeKeepsCache() {
        Long menuId = createMenu(button(0L, "it改名按钮", "it:rename"));
        long before = permissionCacheService.currentVersion(PLATFORM_TENANT_ID);

        asPlatformAdmin(() -> {
            menuService.update(menuId, button(0L, "it改名按钮改", "it:rename"));
            return null;
        });

        assertThat(permissionCacheService.currentVersion(PLATFORM_TENANT_ID))
                .as("权限码没变就不该失效 - 每次改展示名都清全平台缓存是没有必要的开销")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("权限码与自己相同不算冲突(否则任何修改都要重发一次权限码)")
    void updateAllowsSamePermCodeOnSelf() {
        Long menuId = createMenu(button(0L, "it自身权限码", "it:self"));

        asPlatformAdmin(() -> {
            menuService.update(menuId, button(0L, "it自身权限码改名", "it:self"));
            return null;
        });

        asPlatformAdmin(() -> {
            assertThat(menuRepository.findById(menuId).orElseThrow().getMenuName()).isEqualTo("it自身权限码改名");
            return null;
        });
    }

    @Test
    @DisplayName("不能把菜单挂到自己或自己的下级下(成环会让菜单树无限递归)")
    void updateRejectsCycle() {
        Long parentId = createMenu(dir("it父目录"));
        Long childId = createMenu(new MenuSaveRequest(parentId, "it子页面", 2, "it-child", null, null, 1, 1, false));

        assertThatThrownBy(() -> asPlatformAdmin(() -> {
            menuService.update(parentId, new MenuSaveRequest(parentId, "it父目录", 1, "it-parent", null, null, 1, 1, false));
            return null;
        }))
                .as("挂到自己")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能是自己或自己的下级");

        assertThatThrownBy(() -> asPlatformAdmin(() -> {
            menuService.update(parentId, new MenuSaveRequest(childId, "it父目录", 1, "it-parent", null, null, 1, 1, false));
            return null;
        }))
                .as("挂到自己的下级")
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能是自己或自己的下级");
    }

    // ---------------------------------------------------------------- 删除

    @Test
    @DisplayName("有子菜单时拒绝删除(静默裁剪菜单树是事故)")
    void deleteRejectsWhenChildrenExist() {
        Long parentId = createMenu(dir("it待删目录"));
        createMenu(new MenuSaveRequest(parentId, "it子节点", 2, "it-keep-child", null, null, 1, 1, false));

        assertThatThrownBy(() -> asPlatformAdmin(() -> {
            menuService.delete(parentId);
            return null;
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("存在子菜单");
    }

    @Test
    @DisplayName("删除菜单:同一事务里级联清理角色授权与套餐引用")
    void deleteCascadesRoleAndPackageRefs() {
        Long menuId = createMenu(button(0L, "it被引用按钮", "it:referenced"));
        jdbcTemplate.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)", 1L, menuId);
        jdbcTemplate.update("INSERT INTO sys_package_menu (package_id, menu_id) VALUES (?, ?)", 1L, menuId);

        asPlatformAdmin(() -> {
            menuService.delete(menuId);
            return null;
        });

        assertThat(countRows("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = ?", menuId))
                .as("角色授权必须一起清掉,否则留下指向不存在菜单的授权")
                .isZero();
        assertThat(countRows("SELECT COUNT(*) FROM sys_package_menu WHERE menu_id = ?", menuId))
                .isZero();
        asPlatformAdmin(() -> {
            assertThat(menuRepository.findById(menuId)).isEmpty();
            return null;
        });
    }

    @Test
    @DisplayName("删除不存在的菜单:NOT_FOUND")
    void deleteUnknownMenu() {
        assertThatThrownBy(() -> asPlatformAdmin(() -> {
            menuService.delete(999999L);
            return null;
        })).isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ---------------------------------------------------------------- 查询

    @Test
    @DisplayName("菜单树:按状态与类型过滤,且父子关系正确")
    void treeFiltersAndNests() {
        Long parentId = createMenu(dir("it树目录"));
        Long childId = createMenu(new MenuSaveRequest(parentId, "it树按钮", 3, null, "it:tree:btn", null, 1, 1, false));

        List<MenuTreeNode> all = asPlatformAdmin(() -> menuService.tree(null, null));
        MenuTreeNode parent = all.stream().filter(node -> node.id().equals(parentId)).findFirst().orElseThrow();
        assertThat(parent.children()).extracting(MenuTreeNode::id).contains(childId);

        List<MenuTreeNode> buttonsOnly = asPlatformAdmin(() -> menuService.tree(null, 3));
        assertThat(buttonsOnly).extracting(MenuTreeNode::id)
                .as("按类型过滤时只剩按钮(父链不会凭空出现)")
                .contains(childId)
                .doesNotContain(parentId);

        List<MenuTreeNode> disabledOnly = asPlatformAdmin(() -> menuService.tree(0, null));
        assertThat(disabledOnly).as("没有停用菜单时应当为空").isEmpty();

        // 全量树里必须能看到种子数据(first-class 校验:树根是 parentId = 0 的那些)
        List<Long> ids = new ArrayList<>();
        collectIds(all, ids);
        assertThat(ids).as("种子菜单也要在树里").contains(1L);
    }

    private void collectIds(List<MenuTreeNode> nodes, List<Long> sink) {
        for (MenuTreeNode node : nodes) {
            sink.add(node.id());
            if (node.children() != null) {
                collectIds(node.children(), sink);
            }
        }
    }

    private int countRows(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}
