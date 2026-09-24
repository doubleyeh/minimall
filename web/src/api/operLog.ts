import type { Id, PageResult } from '@/types/api'
import type { OperLogView } from '@/types/system'
import { request } from '@/utils/request'

/** 操作日志查询(后端 SysOperLogController,/system/oper-logs)。只读,没有写接口。 */

export interface OperLogPageQuery {
  /** 按租户过滤。只有超管有意义:普通租户用户的查询会被租户过滤再收一次 */
  tenantId?: Id | null
  userId?: Id | null
  module?: string
  status?: number | null
  /** LocalDateTime 字符串(YYYY-MM-DDTHH:mm:ss),不带时区后缀 */
  startTime?: string | null
  endTime?: string | null
  pageNo?: number
  pageSize?: number
}

export function pageOperLogs(query: OperLogPageQuery): Promise<PageResult<OperLogView>> {
  return request.get<PageResult<OperLogView>>('/system/oper-logs', { params: query })
}
