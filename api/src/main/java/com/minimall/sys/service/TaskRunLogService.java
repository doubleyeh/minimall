package com.minimall.sys.service;

import com.minimall.common.PageResult;
import com.minimall.sys.api.dto.TaskRunLogView;
import com.minimall.sys.api.dto.TaskSummaryView;

import java.util.List;

/**
 * 定时任务执行历史(架构文档 6.2)。
 *
 * <p>**只读 + 清理**:历史是观测凭据,人工改它没有意义;清理走保留期,由定时任务调用。
 */
public interface TaskRunLogService {

    /**
     * 执行历史分页查询,最近的在前。
     *
     * @param taskName 任务名精确匹配,可空
     * @param status   1-成功 2-失败,可空
     */
    PageResult<TaskRunLogView> page(String taskName, Integer status, int pageNo, int pageSize);

    /**
     * 每个任务名的最近一次执行情况,按任务名排序。
     *
     * <p>顺带解决一个排查盲点:对着这个清单看每个任务的最后执行时间,就知道谁停了 ——
     * 光看明细列表是看不出"某个任务一条都没跑过"的。
     */
    List<TaskSummaryView> summaries();

    /** 清理保留期之前的记录,返回删除行数。 */
    int prune(int retainDays);
}
