package com.minimall.infra.dict;

import com.minimall.sys.api.dto.DictItemView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 字典读取缓存(架构文档 5.5 的思路用在字典上)。
 *
 * <p><b>为什么字典必须缓存</b>:字典是"读极多、写极少"的数据 —— 前端每个下拉框、
 * 每一次"1 → 待支付"的翻译都要读它,一次业务请求可能读好几个字典类型。
 * 每次都查库会让字典表成为整个系统里 QPS 最高的表,而它的内容一个月都不变一次。
 *
 * <p><b>失效方式:按类型删除,不用版本号</b>。这里与权限缓存(5.5 的租户级版本号)不同,
 * 因为字典的读取粒度就是单个类型:改一个类型只需要删它自己那一把 key,
 * 上版本号反而会让所有类型一起失效、白白重建。字典类型数量是小几十的量级,
 * 删 key 的开销可以忽略。
 *
 * <p><b>空结果也缓存</b>:查一个不存在的字典类型同样会打库,把空列表缓存住可以挡住
 * "前端写错编码 → 每次渲染都查库"这类无意义的穿透。TTL 取短一些,给配置留出纠错时间。
 *
 * <p>存储编码:项与项之间用 {@code \n},标签与值之间用 {@code \u0001}。
 * 不引 JSON 库是因为字典项只有两个字段,而这两个字符不可能出现在字典标签/值里
 * (它们会被 {@code @Size} 与业务校验挡住),省一次序列化反序列化。
 */
@Component
public class DictCacheService {

    private static final Logger log = LoggerFactory.getLogger(DictCacheService.class);

    private static final String KEY_PREFIX = "dict:";
    private static final String FIELD_SEPARATOR = "\u0001";
    private static final String ENTRY_SEPARATOR = "\n";

    /** 命中缓存的正常 TTL:字典内容极少变动,1 小时足够,写入侧还会主动删 key。 */
    private static final Duration TTL = Duration.ofHours(1);

    /**
     * 空结果的 TTL:刻意短很多。它缓存的是"这个类型不存在",
     * 而"不存在"多半是配置漏了或编码写错了 —— 两分钟内补上配置就应该立刻生效。
     */
    private static final Duration EMPTY_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redis;

    public DictCacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<List<DictItemView>> get(String dictType) {
        String value = redis.opsForValue().get(key(dictType));
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(decode(value));
    }

    public void put(String dictType, List<DictItemView> items) {
        String value = encode(items);
        redis.opsForValue().set(key(dictType), value, items.isEmpty() ? EMPTY_TTL : TTL);
    }

    /**
     * 删除某类型的缓存。字典类型/数据的任何增删改都必须调用它 ——
     * 漏掉的后果是"配置改了但前端还显示旧标签",而且因为 TTL 是 1 小时,能持续很久。
     */
    public void evict(String dictType) {
        redis.delete(key(dictType));
        log.info("字典缓存已失效 dictType={}", dictType);
    }

    private String key(String dictType) {
        return KEY_PREFIX + dictType;
    }

    private String encode(List<DictItemView> items) {
        if (items.isEmpty()) {
            // 空列表编码成空串。用"key 存在且值为空串"表示"确实没有字典项",
            // 与"key 不存在(缓存未命中)"区分开 —— 否则空结果缓存永远不起作用。
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (DictItemView item : items) {
            if (!builder.isEmpty()) {
                builder.append(ENTRY_SEPARATOR);
            }
            builder.append(item.label()).append(FIELD_SEPARATOR).append(item.value());
        }
        return builder.toString();
    }

    private List<DictItemView> decode(String value) {
        if (value.isEmpty()) {
            return List.of();
        }
        List<DictItemView> items = new ArrayList<>();
        for (String entry : value.split(ENTRY_SEPARATOR)) {
            int index = entry.indexOf(FIELD_SEPARATOR);
            if (index < 0) {
                // 缓存被外部写坏:返回空而不是抛异常,别把业务接口打成 500
                log.warn("字典缓存内容异常,已忽略: {}", entry);
                continue;
            }
            items.add(new DictItemView(entry.substring(0, index), entry.substring(index + 1)));
        }
        return items;
    }
}
