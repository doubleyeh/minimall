package com.minimall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import com.minimall.sys.service.AuthService;
import com.minimall.sys.service.DeptService;
import com.minimall.sys.service.MenuService;
import com.minimall.sys.service.PackageService;
import com.minimall.sys.service.RoleService;
import com.minimall.sys.service.TenantService;
import com.minimall.sys.service.UserService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用上下文能否装配起来(比单元测试更早发现"Bean 缺失/循环依赖/配置项拼错"这类问题)。
 *
 * <p>它同时验证了这些"只有真起来才发现得了"的东西:
 * <ul>
 *   <li>Flyway 迁移脚本真的能在 MySQL 上跑通(测试库由脚本建,不是手工建的)</li>
 *   <li>Hibernate 的 {@code ddl-auto = validate} 通过 —— 即实体映射与建表脚本一致</li>
 *   <li>7 个 service 实现都能被找到(Controller 依赖的接口都有实现)</li>
 *   <li>切面、过滤器、Sa-Token、Redis 这些配置项拼写正确</li>
 * </ul>
 *
 * <p>依赖真实 MySQL/Redis(Flyway 会建库表),所以打 {@code integration} 标签。
 * 运行前测试库应当由 Flyway 从空库建起(见 9.4)。
 */
@Tag("integration")
@SpringBootTest
@ActiveProfiles("dev")
class ApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private AuthService authService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private PackageService packageService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private UserService userService;
    @Autowired
    private DeptService deptService;
    @Autowired
    private MenuService menuService;

    @Test
    @DisplayName("上下文装配成功且 7 个 service 都能注入")
    void contextLoadsWithAllServices() {
        assertThat(context).isNotNull();
        assertThat(authService).isNotNull();
        assertThat(tenantService).isNotNull();
        assertThat(packageService).isNotNull();
        assertThat(roleService).isNotNull();
        assertThat(userService).isNotNull();
        assertThat(deptService).isNotNull();
        assertThat(menuService).isNotNull();
    }
}
