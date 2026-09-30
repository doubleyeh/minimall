import type { SalesStatReport } from '@/types/mall'
import { request } from '@/utils/request'

/**
 * 销售统计(后端 MallSalesStatController,/mall/admin/sales-stats)。
 *
 * 日期区间必填,按**下单时间**过滤;退款额另按退款成功时间统计(后端会在页面上说明口径)。
 */
export function salesStatReport(startTime: string, endTime: string): Promise<SalesStatReport> {
  return request.get<SalesStatReport>('/mall/admin/sales-stats', { params: { startTime, endTime } })
}
