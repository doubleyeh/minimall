package com.minimall.infra.idempotent;

import com.minimall.common.ErrorCode;
import com.minimall.infra.audit.AuditContext;
import com.minimall.infra.tenant.TenantWebFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 幂等键(架构文档 7.7)。
 *
 * <p><b>解决什么</b>:网络超时、用户连点、客户端自动重试都可能让同一个写请求到达两次。
 * 前端有"提交中禁用按钮",但那挡不住"请求已经发出去、响应没回来"这一类 —— 客户端只能重试,
 * 而重试在服务端看来和"再提交一次"没有区别。幂等键把"这是一次重试"这个信息从客户端带上来。
 *
 * <p><b>怎么用</b>:写请求(POST/PUT/PATCH/DELETE)带一个 {@code Idempotency-Key} 头,值由客户端生成
 * (同一个业务意图复用它,重试要沿用同一个值)。**不带这个头就完全按老样子执行** —— 所以这是可选能力,
 * 不影响任何既有调用方,也不需要逐个接口标注。
 *
 * <p><b>三种结果</b>:
 * <ol>
 *   <li>键没用过 → 正常执行,成功后把响应体按 TTL 存下来</li>
 *   <li>键用过且上次成功 → **直接回放上次的响应**(客户端拿到和第一次一样的结果,不会再写一次)</li>
 *   <li>键用过但上次还在跑 → 拒绝(40901),避免"同一秒的两个请求都写进去"</li>
 * </ol>
 *
 * <p><b>只回放成功的 JSON 响应</b>:失败(业务码非 0)不留痕,让客户端能改完参数用同一个键重试;
 * 文件下载这类二进制响应只挡并发重复、不做回放(存二进制要换存法,当前没有这样的写接口)。
 */
@Component
@Order(IdempotencyFilter.ORDER)
public class IdempotencyFilter extends OncePerRequestFilter {

    /**
     * 必须晚于 {@link TenantWebFilter}(以及它后面的客户端鉴权过滤器):
     * 幂等键要按"谁 + 哪个接口"隔离,得先有租户与身份。
     */
    public static final int ORDER = TenantWebFilter.ORDER + 10;

    public static final String HEADER = "Idempotency-Key";
    /** 回放时告诉调用方"这是上次的结果",便于排查与联调 */
    public static final String REPLAY_HEADER = "X-Idempotent-Replay";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final String KEY_PREFIX = "idempotent:";
    private static final String IN_PROGRESS = "\u0000IN_PROGRESS";
    /** 统一响应体的第一个字段就是 code(见 ApiResponse),所以只看开头即可,不必真解 JSON。 */
    private static final Pattern SUCCESS_ENVELOPE = Pattern.compile("^\\s*\\{\\s*\"code\"\\s*:\\s*0\\b");

    private final StringRedisTemplate redis;
    private final IdempotencyProperties properties;

    public IdempotencyFilter(StringRedisTemplate redis, IdempotencyProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String idempotencyKey = request.getHeader(HEADER);
        if (idempotencyKey == null || idempotencyKey.isBlank() || !isStateChanging(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String key = redisKey(request, idempotencyKey);
        String stored = redis.opsForValue().get(key);
        if (IN_PROGRESS.equals(stored)) {
            log.warn("幂等键正在处理中,已拒绝重复请求 key={} uri={}", idempotencyKey, request.getRequestURI());
            writeDuplicate(response);
            return;
        }
        if (stored != null) {
            // 已经跑完过:回放上次的结果,不再执行
            replay(stored, response);
            return;
        }
        Boolean acquired = redis.opsForValue().setIfAbsent(key, IN_PROGRESS, ttl());
        if (!Boolean.TRUE.equals(acquired)) {
            // 上面那次 get 之后到这里的极短窗口里,另一个线程抢到了 —— 或者它本来就在跑
            log.warn("幂等键正在处理中,已拒绝重复请求 key={} uri={}", idempotencyKey, request.getRequestURI());
            writeDuplicate(response);
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(request, wrapper);
            completed = true;
        } finally {
            if (completed && shouldKeep(wrapper)) {
                redis.opsForValue().set(key, bodyOf(wrapper), ttl());
            } else {
                // 失败不留痕:让客户端改完参数用同一个键重试(留痕的话重试永远拿到同一个错误)
                redis.delete(key);
            }
            // 必须回写:内容被 wrapper 缓存住了,不写回客户端拿到的是空响应
            wrapper.copyBodyToResponse();
        }
    }

    private boolean isStateChanging(String method) {
        return "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
    }

    /** 键里带租户、用户、方法与路径:同一个键换个接口或换个人都不算重复。 */
    private String redisKey(HttpServletRequest request, String idempotencyKey) {
        AuditContext audit = AuditContext.current();
        return redisKeyOf(audit == null ? null : audit.tenantId(),
                audit == null ? null : audit.userId(),
                request.getMethod(), request.getRequestURI(), idempotencyKey);
    }

    /** 键的格式只在这一处:排查时对着 Redis 里的键就能还原出"谁、调了哪个接口"。 */
    static String redisKeyOf(Long tenantId, Long userId, String method, String uri, String idempotencyKey) {
        return KEY_PREFIX + (tenantId == null ? "-" : tenantId)
                + ":" + (userId == null ? "-" : userId)
                + ":" + method
                + ":" + uri
                + ":" + idempotencyKey;
    }

    /** 只保留成功的 JSON:失败和不做回放的二进制都只挡并发重复。 */
    private boolean shouldKeep(ContentCachingResponseWrapper wrapper) {
        String contentType = wrapper.getContentType();
        if (contentType == null || !contentType.contains("json")) {
            return false;
        }
        return SUCCESS_ENVELOPE.matcher(bodyOf(wrapper)).find();
    }

    private String bodyOf(ContentCachingResponseWrapper wrapper) {
        return new String(wrapper.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void replay(String stored, HttpServletResponse response) throws IOException {
        response.setHeader(REPLAY_HEADER, "true");
        writeJson(response, stored);
    }

    private void writeDuplicate(HttpServletResponse response) throws IOException {
        writeJson(response, "{\"code\":" + ErrorCode.DUPLICATE_REQUEST.code()
                + ",\"message\":\"" + ErrorCode.DUPLICATE_REQUEST.message() + "\"}");
    }

    /** 与 TenantWebFilter 同一套写法:过滤器在 DispatcherServlet 之外,走不到 @RestControllerAdvice。 */
    private void writeJson(HttpServletResponse response, String body) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }

    private Duration ttl() {
        return Duration.ofSeconds(properties.ttlSeconds());
    }
}
