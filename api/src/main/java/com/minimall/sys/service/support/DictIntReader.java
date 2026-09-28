package com.minimall.sys.service.support;

import com.minimall.sys.api.dto.DictItemView;
import com.minimall.sys.service.DictService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 从字典读整数阈值(架构文档 3.4/3.9):取不到、越界、或值不是整数时一律退回默认值。
 *
 * <p>配置问题不该让调用方整体卡住 —— 这是"阈值走字典"这个决定必须付出的代价的一半。
 */
@Component
public class DictIntReader {

    private static final Logger log = LoggerFactory.getLogger(DictIntReader.class);

    private final DictService dictService;

    public DictIntReader(DictService dictService) {
        this.dictService = dictService;
    }

    public int get(String dictType, int defaultValue) {
        return getAt(dictType, 0, defaultValue);
    }

    /**
     * 读字典里第 {@code index} 个条目的整数值。
     *
     * <p>一个字典类型下有多档配置时(如 {@code after_sale_timeout} 有三档)只能按顺序取 ——
     * 条目按 {@code sort_order} 返回,所以下标与迁移脚本里种子数据的顺序对应。
     */
    public int getAt(String dictType, int index, int defaultValue) {
        try {
            List<DictItemView> items = dictService.items(dictType);
            if (items.size() <= index) {
                return defaultValue;
            }
            return Integer.parseInt(items.get(index).value().trim());
        } catch (NumberFormatException ex) {
            log.warn("字典 {} 第 {} 项不是整数,已使用默认值 {}", dictType, index, defaultValue);
            return defaultValue;
        }
    }
}
