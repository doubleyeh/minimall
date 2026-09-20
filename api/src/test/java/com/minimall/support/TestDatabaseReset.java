package com.minimall.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每次测试运行**开始前重置一次数据库**:删掉全部表,再让 Flyway 重新执行全部迁移。
 *
 * <h2>为什么需要它</h2>
 * 用例里的清理只能解决"自己造的数据自己收",解决不了两类问题:
 * <ol>
 *   <li><b>跨运行的累积</b>。只要有一个用例忘了清理(或清理不完整),数据就会留到下一次运行。
 *       实测踩过:会员等级用例的等级名只用了四位随机后缀且从不清理,累积到一定数量后
 *       建新等级必然撞唯一约束 —— 表现是"某个用例偶尔失败",而且只在共用过的库上出现</li>
 *   <li><b>依赖"表里只有自己的数据"的断言</b>。例如断言列表接口返回 1 条,历史数据会让它变成 2 条</li>
 * </ol>
 *
 * <h2>为什么是"删表 + 重新迁移"而不是 TRUNCATE</h2>
 * 用例依赖种子数据(平台租户、全量套餐、商城菜单、字典、种子管理员)。
 * 只清空数据会把种子一起清掉,测试反而全挂;必须让 V1..Vn 重新跑一遍把种子补回来。
 *
 * <h2>安全边界(重要)</h2>
 * 这是**破坏性操作**,所以有两道闸:
 * <ol>
 *   <li><b>库名白名单</b>:只有库名里含 {@code test} 或 {@code ci} 才动手。
 *       万一有人把 {@code SPRING_DATASOURCE_URL} 指到了开发库,这里会跳过并打警告,
 *       而不是把开发数据删掉</li>
 *   <li><b>每次运行只执行一次</b>:用静态标志位保证,不是每个测试类都重置一遍
 *       (那会让整个套件慢到不可接受,也会让"类之间互相污染"换个形式重新出现)</li>
 * </ol>
 *
 * <p>注册方式见 {@code src/test/resources/META-INF/services}(JUnit 的扩展自动发现),
 * 这样不需要在每个测试类上写 {@code @ExtendWith} —— 靠"记得加注解"来保证的约定,
 * 迟早会漏掉新写的类。
 */
public class TestDatabaseReset implements BeforeAllCallback {

    private static final Logger log = LoggerFactory.getLogger(TestDatabaseReset.class);
    private static final AtomicBoolean DONE = new AtomicBoolean();

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!DONE.compareAndSet(false, true)) {
            return;
        }
        String url = resolveUrl();
        String database = databaseName(url);
        if (database == null || !database.toLowerCase().matches(".*(test|ci).*")) {
            // 宁可什么都不做,也不能把非测试库删了
            log.warn("跳过测试前的库重置:库名 [{}] 不在白名单里(需含 test 或 ci)。url={}", database, url);
            return;
        }
        try (Connection connection = DriverManager.getConnection(url, resolveUsername(), resolvePassword())) {
            List<String> tables = listTables(connection, database);
            dropAll(connection, tables);
            log.info("测试前库重置:已删除 {} 张表,准备重新执行迁移。库={}", tables.size(), database);
        } catch (Exception ex) {
            // 重置失败就让用例照常跑:让用例自己失败并给出真实原因,比在这里抛异常更有信息量
            log.warn("测试前的库重置失败,用例将沿用现有数据继续执行", ex);
            return;
        }
        Flyway.configure()
                .dataSource(url, resolveUsername(), resolvePassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        log.info("测试前库重置完成,种子数据已重新播种");
    }

    private List<String> listTables(Connection connection, String database) throws Exception {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT table_name FROM information_schema.tables WHERE table_schema = '" + database + "'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    /**
     * 删表前关掉外键检查。
     *
     * <p>本项目表之间有大量外键(订单 → 明细 → 商品……),不关掉的话删除顺序会互相牵制,
     * 只能靠拓扑排序去挑顺序 —— 而这个顺序一旦有人加表就会失效。
     */
    private void dropAll(Connection connection, List<String> tables) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : tables) {
                statement.execute("DROP TABLE IF EXISTS `" + table + "`");
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    /**
     * 连接串解析顺序:环境变量 → 测试环境默认值。
     *
     * <p>与 {@code application-test.yml} 的取值口径保持一致;用例实际连的库由
     * {@code SPRING_DATASOURCE_URL} 决定,所以这里优先读它。
     */
    private String resolveUrl() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url != null && !url.isBlank()) {
            return url;
        }
        String host = envOrDefault("TEST_MYSQL_HOST", "127.0.0.1");
        String database = envOrDefault("TEST_MYSQL_DB", "minimall_test");
        return "jdbc:mysql://" + host + ":3306/" + database
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private String resolveUsername() {
        String username = System.getenv("SPRING_DATASOURCE_USERNAME");
        return username != null && !username.isBlank() ? username : envOrDefault("TEST_MYSQL_USER", "root");
    }

    private String resolvePassword() {
        String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
        return password != null && !password.isBlank() ? password : envOrDefault("TEST_MYSQL_PASSWORD", "123456");
    }

    private String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 从 {@code jdbc:mysql://host:3306/库名?参数} 里取出库名。
     *
     * <p>**必须先切掉查询串再找最后一个斜杠**:参数里的 {@code serverTimezone=Asia/Shanghai}
     * 自带斜杠,直接取最后一个斜杠会把库名解析成 {@code Shanghai}。
     * (这个错误第一次就被守卫拦住了 —— 它拒绝执行,而不是拿着错库名去删表。)
     */
    private String databaseName(String url) {
        String withoutQuery = url;
        int question = url.indexOf('?');
        if (question >= 0) {
            withoutQuery = url.substring(0, question);
        }
        int slash = withoutQuery.lastIndexOf('/');
        if (slash < 0) {
            return null;
        }
        String name = withoutQuery.substring(slash + 1);
        return name.isBlank() ? null : name;
    }
}
