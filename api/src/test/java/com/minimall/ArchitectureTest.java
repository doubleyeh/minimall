package com.minimall;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
 *   <li><b>顶层包只能是 mall / sys / infra / common</b> —— 分包口径不设边界,新代码会随手建出第五个
 *       顶层包,几轮之后约定就名存实亡</li>
 *   <li><b>Controller 必须位于某个域的 api 包下</b> —— 否则"服务入口在哪"没有确定答案,
 *       按包收敛拦截器/权限规则的思路也不再成立</li>
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
        // 覆盖基础设施与商城两侧的实现包。新增业务模块时把它的 impl 包加进来 ——
        // 漏一个包,那个模块的查询就在"过滤器未启用"的状态下执行(见下面的 because)。
        classes().that().resideInAnyPackage("..sys.service.impl..", "..mall.service.impl..")
                // 只看顶层类:实现类里的小 record(如 OrderServiceImpl 内部的 Line/CouponUse)
                // 是纯粹的传值载体,不需要也不应该标事务注解 —— 不加这一条会把它们一起算成违规
                .and().areTopLevelClasses()
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

    @Test
    @DisplayName("顶层包只能是 mall / sys / infra / common")
    void topLevelPackagesAreLimited() {
        classes().that().resideInAPackage("com.minimall..")
                // 启动类是唯一允许待在根包的:它必须位于所有组件扫描包的父级
                .and().areNotAnnotatedWith(SpringBootApplication.class)
                .should().resideInAnyPackage(
                        "com.minimall.mall..", "com.minimall.sys..",
                        "com.minimall.infra..", "com.minimall.common..")
                .because("""
                        四个顶层包的分工:mall 与 sys 是业务域(各自按 api → service → domain → infra 纵向分包)、
                        infra 是跨域技术设施(租户过滤、鉴权、审计、ID 生成、缓存、持久化基类)、
                        common 是通用返回与异常。
                        这条规则的价值不在当下,而在下一个模块:人的默认行为是随手新建一个顶层包,
                        而"随手建的包"正是这次分包统一的起因。""")
                .check(classes);
    }

    @Test
    @DisplayName("Controller 必须位于某个域的 api 包下(mall.api / sys.api)")
    void controllersLiveInApiPackages() {
        classes().that().areAnnotatedWith(RestController.class)
                .or().areAnnotatedWith(Controller.class)
                .should().resideInAPackage("..api..")
                .because("接口层统一放在所属业务域的 api 包下;散落各处会让"
                        + "\"服务入口在哪\"没有确定答案,按包收敛拦截器与权限规则的思路也不再成立")
                .check(classes);
    }
}
