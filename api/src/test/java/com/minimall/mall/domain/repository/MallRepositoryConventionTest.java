package com.minimall.mall.domain.repository;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商城仓储的约定检查(架构文档 4.2 的安全约束)。
 *
 * <p><b>为什么必须有一条自动规则</b>:所有 {@code mall_*} 实体都挂着租户过滤器,
 * 而 Hibernate 的 {@code @Filter} 只作用于查询 —— 默认的 {@code findById} 走
 * {@code EntityManager.find()},生成的 SQL 里没有任何租户条件。漏掉覆盖不会报错、
 * 不会让任何用例变红,只会安静地留下一条"凭 ID 就能跨租户读写"的路径。
 * 所以把它固化成构建期的硬约束:新增仓储忘了覆盖,构建直接失败。
 *
 * <p>检查方式:扫描 {@code mall.domain.repository} 下的接口,要求每个接口
 * **自己声明**了 {@code findById}(继承来的不算,因为继承来的正是那个不安全的默认实现)。
 */
class MallRepositoryConventionTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.minimall.mall.domain.repository");
    }

    @Test
    @DisplayName("每个 mall 仓储都必须自己覆盖 findById(否则按 ID 直查会绕过租户过滤)")
    void everyRepositoryDeclaresOwnFindById() {
        ArchCondition<JavaClass> declareOwnFindById = new ArchCondition<>("自己声明 findById") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean declared = item.getMethods().stream()
                        .anyMatch(method -> "findById".equals(method.getName())
                                && method.getOwner().getName().equals(item.getName()));
                if (!declared) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getSimpleName() + " 没有覆盖 findById:"
                                    + "Hibernate 过滤器不作用于主键直查,必须改用 findOne(Q.id.eq(id))"));
                }
            }
        };

        classes().that().resideInAPackage("com.minimall.mall.domain.repository")
                .and().areInterfaces()
                // 只认 XxxRepository 这个命名约定。顺带把 package-info 排除掉:
                // 它在字节码里也是 ACC_INTERFACE,会被 areInterfaces() 命中 ——
                // 不排除的话,规则会要求一个包说明文件实现 findById。
                .and().haveSimpleNameEndingWith("Repository")
                .should(declareOwnFindById)
                .because("租户隔离的实体的仓储若用默认 findById,等于留了一条按 ID 跨租户读写的路径(架构文档 4.2)")
                .check(classes);
    }

    @Test
    @DisplayName("仓储接口数量与 mall 实体数量一致 —— 防止新增实体时漏建仓储")
    void repositoryCountMatchesEntities() {
        long repositoryCount = classes.stream()
                .filter(JavaClass::isInterface)
                .filter(javaClass -> javaClass.getSimpleName().endsWith("Repository"))
                .count();
        // 28 张 mall_* 表对应 28 个仓储;数量变化时这条用例会提醒去核对(新建/删除表都要同步)
        assertThat(repositoryCount)
                .as("mall 仓储数量与 mall_* 表数量应当一致(当前 28 张表),新增表时同步建仓储")
                .isEqualTo(28L);
    }
}
