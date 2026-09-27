package com.minimall.sys.service.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 租户数据的物理删除(架构文档 4.11)。
 *
 * <p>与 {@link TenantDataExporter} 共用 {@link TenantDataScope} 的清单,但取的是
 * {@code deletableTables()}:**审计日志不删**(见该类里 RETAINED_TABLES 的说明)——
 * 业务数据都删了,日志是"这个租户当时做过什么"的唯一凭据。
 *
 * <p>用 JdbcTemplate 而不是 JPA:①租户数据横跨 34 张表,逐个建实体层方法不现实;
 * ②删除必须**真的删掉**——走实体的软删/审计钩子会留下"看起来删了"的行,而这是不可逆操作的最后一步。
 */
@Component
public class TenantDataPurger {

    private static final Logger log = LoggerFactory.getLogger(TenantDataPurger.class);

    private final JdbcTemplate jdbcTemplate;
    private final TenantDataScope dataScope;

    public TenantDataPurger(JdbcTemplate jdbcTemplate, TenantDataScope dataScope) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataScope = dataScope;
    }

    /**
     * 删掉这个租户的全部数据(含租户行本身)。
     *
     * @return 删除的总行数,用于日志与"到底删干净没有"的排查(不含保留的审计日志)
     */
    public int purge(Long tenantId) {
        int total = 0;
        for (String table : dataScope.deletableTables()) {
            int deleted = jdbcTemplate.update(dataScope.deleteSql(table), tenantId);
            total += deleted;
            log.info("清理租户数据 tenantId={} 表={} 行数={}", tenantId, table, deleted);
        }
        // 租户行放最后:前面按 tenant_id 反查的语句都还依赖它
        total += jdbcTemplate.update("delete from tenant where id = ?", tenantId);
        return total;
    }
}
