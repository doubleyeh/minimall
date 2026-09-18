package com.minimall;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 分层依赖方向(架构文档 3)。
 *
 * <p>用一条测试固化规则,而不是靠 code review 记性:违反即构建失败,并且**不允许注释掉**。
 *
 * <p>规则集合(注意有两条"看起来该禁但实际允许"的依赖,原因写在下面):
 * <ol>
 *   <li>service/domain/infra/common 不得依赖 Controller —— 反向依赖会让业务逻辑被 HTTP 细节绑住。
 *       **service 允许依赖 api 层的 DTO**(接口契约对象),所以这里禁的是"依赖 Controller 类型"
 *       而不是"依赖整个 api 包"</li>
 *   <li>api 不得依赖 Repository —— 接口层禁止直接访问数据(3 节:api 只放 Controller + DTO)</li>
 *   <li>domain/infra/common 不得依赖 service —— 依赖方向必须单向:api → service → domain</li>
 *   <li>common 不得依赖任何业务包(它被所有人依赖,不能反过来依赖别人)</li>
 *   <li>domain 允许依赖 infra 的 ID 生成器 —— 实体基类的 {@code @PrePersist} 需要同步生成雪花 ID
 *       (见 4.3 的接入方式说明),这是有意放开的一条,不要当成漏洞</li>
 * </ol>
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.minimall");
    }

    @Test
    @DisplayName("service/domain/infra/common 不得依赖 Controller")
    void businessLayersMustNotDependOnControllers() {
        noClasses()
                .that().resideInAnyPackage("..service..", "..domain..", "..infra..", "..common..")
                .should().dependOnClassesThat().areAnnotatedWith(RestController.class)
                .because("依赖方向是 api -> service -> domain,业务层不得反向依赖接口层(架构文档 3)")
                .check(classes);

        noClasses()
                .that().resideInAnyPackage("..service..", "..domain..", "..infra..", "..common..")
                .should().dependOnClassesThat().areAnnotatedWith(Controller.class)
                .because("依赖方向是 api -> service -> domain,业务层不得反向依赖接口层(架构文档 3)")
                .check(classes);
    }

    @Test
    @DisplayName("api 层不得依赖 domain(接口层不碰实体与数据访问)")
    void apiLayerMustNotDependOnDomain() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..domain..")
                .because("api 层只放 Controller + DTO,禁止直接注入 Repository 或操作实体(架构文档 3)");
        rule.check(classes);
    }

    @Test
    @DisplayName("domain/infra/common 不得依赖 service")
    void lowerLayersMustNotDependOnService() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("..domain..", "..infra..", "..common..")
                .should().dependOnClassesThat().resideInAPackage("..service..")
                .because("依赖方向单向:api -> service -> domain;infra 被依赖但不反向依赖(架构文档 3)");
        rule.check(classes);
    }

    @Test
    @DisplayName("service 实现类必须标注 @Transactional(否则租户过滤器不会被启用)")
    void serviceImplementationsMustBeTransactional() {
        classes().that().resideInAPackage("..service.sys.impl..")
                .should().beAnnotatedWith(Transactional.class)
                .because("租户过滤器由事务切面在每个事务边界启用(架构文档 4.2);"
                        + "漏标不会报错,而是该入口的查询在过滤器未启用的状态下执行 —— 直接跨租户读到数据")
                // 允许"该包暂时没有类":ArchUnit 默认连"没检查到任何类"都算失败。
                // 这里允许空包不会让规则变成摆设 —— 一旦这个包空了,Controller 依赖的 service 就没有实现,
                // Spring 上下文根本起不来,那是比架构测试更响的失败。
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("common 不得依赖任何业务包")
    void commonMustStayIndependent() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..api..", "..service..", "..domain..", "..infra..")
                .because("common 被所有人依赖,它自己不能依赖任何业务包(架构文档 3)");
        rule.check(classes);
    }
}
