package com.minimall.infra.tenant;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 过滤器开关逻辑(架构文档 4.2)。
 *
 * <p>为什么单独抽出来、并且不依赖 HTTP:测试时可以直接调用它验证"上下文设对了但 Hibernate 层有没有生效"
 * (8.1 用例 6),不需要起真实请求。这是整套隔离机制可测试性的关键一环。
 *
 * <p><b>启用规则只有两条(默认拒绝,只有超管放开)</b>:
 * <ul>
 *   <li>{@code isSuperUser = true}:两个过滤器都不启用 —— 这是唯一豁免</li>
 *   <li>其他所有情况:一律启用。{@code tenantId} 非空时绑该值;为空时绑哨兵 {@link #NO_TENANT_SENTINEL},
 *       使条件退化成 {@code tenant_id = -1},租户表查询返回空集而不是全集。
 *       漏设上下文的故障表现因此是"查不到数据"(立即暴露)而不是"看到所有人的数据"(数据泄露)</li>
 * </ul>
 *
 * <p>重复 enable 是幂等的(Hibernate 的 enableFilter 只是覆盖启用表中的条目),
 * 所以 HTTP 入口和事务切面都调用它、没有任何副作用(4.2)。
 */
@Component
public class TenantFilterService {

    /** 过滤器的名字必须与 {@code com.minimall.domain.sys.package-info} 里的 @FilterDef 完全一致。 */
    public static final String TENANT_FILTER = "tenantFilter";
    public static final String DATA_SCOPE_FILTER = "dataScopeFilter";

    /** 未定租户时绑定的哨兵值:不存在的租户 ID,使租户表查询命中空集。 */
    public static final long NO_TENANT_SENTINEL = -1L;

    private static final Logger log = LoggerFactory.getLogger(TenantFilterService.class);

    private final DataScopeProvider dataScopeProvider;

    public TenantFilterService(DataScopeProvider dataScopeProvider) {
        this.dataScopeProvider = dataScopeProvider;
    }

    /**
     * 根据当前 {@link TenantContext} 状态,在传入 EntityManager 对应的 Session 上启用/禁用过滤器。
     *
     * <p><b>调用时机</b>:必须在 Session 创建/事务开启之后调用。HTTP 路径由 TenantWebFilter 调一次
     * (靠 open-in-view 保证整个请求同一个 Session);异步路径必须在每个事务边界重新调用
     * (见 4.2 与 {@code TenantFilterAspect})。
     */
    public void apply(EntityManager entityManager) {
        Session session = entityManager.unwrap(Session.class);

        if (TenantContext.isSuperUser()) {
            session.disableFilter(TENANT_FILTER);
            session.disableFilter(DATA_SCOPE_FILTER);
            if (log.isDebugEnabled()) {
                log.debug("超管上下文:已禁用租户过滤与数据权限过滤");
            }
            return;
        }

        Long tenantId = TenantContext.getTenantId();
        Long boundTenantId = tenantId == null ? NO_TENANT_SENTINEL : tenantId;
        session.enableFilter(TENANT_FILTER).setParameter("tenantId", boundTenantId);

        // 身份前置查询(登录、刷新令牌换票):此刻线程里**还没有身份**,数据范围无从计算。
        // 若照常启用,按 username/id 读 sys_user 的语句会被 denyAll 拦成空集,
        // 表现为"账号密码都正确却登录失败"——这个故障只在真实 HTTP 链路上出现,
        // 服务层单测(自己绑了审计上下文)看不见,见 DataScopeBypass 的说明。
        // 注意只跳过数据权限,**租户过滤照旧生效**(4.1 的租户隔离任何情况下都不豁免)。
        if (DataScopeBypass.isActive()) {
            session.disableFilter(DATA_SCOPE_FILTER);
            if (log.isDebugEnabled()) {
                log.debug("身份前置查询:仅启用租户过滤,已跳过数据权限过滤");
            }
            return;
        }

        // 关键顺序:先把数据权限过滤器关掉,再去计算本轮的参数。
        // 原因是 provider 要通过 sys_user 读"当前用户的部门与角色",而 sys_user 恰好是数据权限过滤的目标表:
        // HTTP 路径下 open-in-view 让整个请求共用一个 Session,上一条事务 enable 过的 dataScopeFilter
        // 会带着**上一轮的旧参数**继续生效(例如上一轮是 denyAll),于是这里查不到用户 → 参数退化成 denyAll
        // → 后续所有查询都返回空集。这类"自己把自己过滤没了"的问题只看代码很难发现,
        // 所以把顺序固定下来并留在这里说明。
        session.disableFilter(DATA_SCOPE_FILTER);

        DataScopeParams params = dataScopeProvider.current();
        session.enableFilter(DATA_SCOPE_FILTER)
                .setParameter("dataScope", params.dataScope())
                .setParameter("currentUserId", params.currentUserId())
                .setParameter("currentDeptId", params.currentDeptId())
                .setParameter("deptPathLike", params.deptPathLike());

        if (log.isDebugEnabled()) {
            log.debug("已应用过滤 tenantId={}(原值={}) dataScope={}", boundTenantId, tenantId, params.dataScope());
        }
    }

    /**
     * 显式关闭全部过滤。只给"确实需要跨租户扫描"的平台级运维场景用,
     * 业务代码不要调用——正常路径应该用超管上下文({@code runAsTenant(..., true, ...)})表达意图。
     */
    public void disableAll(EntityManager entityManager) {
        Session session = entityManager.unwrap(Session.class);
        session.disableFilter(TENANT_FILTER);
        session.disableFilter(DATA_SCOPE_FILTER);
    }
}
