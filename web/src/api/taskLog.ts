import type { PageResult } from '@/types/api'
import type { TaskRunLogView, TaskSummaryView } from '@/types/system'
import { request } from '@/utils/request'

/** 定时任务执行历史(后端 SysTaskLogController,/system/task-run-logs)。只读。 */

export interface TaskRunLogPageQuery {
  /** 任务名精确匹配 */
  taskName?: string
  /** 1-成功 2-失败 */
  status?: number | null
  pageNo?: number
  pageSize?: number
}

export function pageTaskRunLogs(query: TaskRunLogPageQuery): Promise<PageResult<TaskRunLogView>> {
  return request.get<PageResult<TaskRunLogView>>('/system/task-run-logs', { params: query })
}

/**
 * 每个任务的最近一次执行情况。
 *
 * 页面的主要价值在这里:只翻明细是看不出"某个任务停了"的,那要逐个任务名筛一遍。
 */
export function taskRunSummaries(): Promise<TaskSummaryView[]> {
  return request.get<TaskSummaryView[]>('/system/task-run-logs/summaries')
}
