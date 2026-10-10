package com.minimall.infra.schedule;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可手动触发的定时任务名单(架构文档 6.2)。
 *
 * <p><b>只有注册进来的任务才能被接口触发</b> —— 危险任务(物理删数据、归档日志、批量禁用租户)
 * 根本不注册,所以不存在"接口被调用就跑起来"的可能,不依赖前端隐藏按钮。
 *
 * <p>注册的只是入口方法本身,任务内部照旧抢 Redis 锁:手动触发与到点执行天然互斥,
 * 已有实例在跑时手动那次会被跳过并记进执行历史。
 */
@Component
public class ManualTaskRegistry {

    private final Map<String, Runnable> tasks = new ConcurrentHashMap<>();

    public void register(String taskName, Runnable action) {
        tasks.put(taskName, action);
    }

    /** 可手动触发的任务名(前端据此决定显示哪些按钮)。 */
    public List<String> names() {
        return tasks.keySet().stream().sorted().toList();
    }

    public void trigger(String taskName) {
        Runnable action = tasks.get(taskName);
        if (action == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该任务不支持手动触发: " + taskName);
        }
        action.run();
    }
}
