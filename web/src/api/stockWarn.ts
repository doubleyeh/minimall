import type { StockWarnReport } from '@/types/mall'
import { request } from '@/utils/request'

/**
 * 库存预警(后端 MallStockWarnController,/mall/admin/stock-warns)。只读。
 *
 * 阈值在字典 `stock_warn_threshold` 里配,响应里会带回本次用的值。
 */

export function pageStockWarns(pageNo: number, pageSize: number): Promise<StockWarnReport> {
  return request.get<StockWarnReport>('/mall/admin/stock-warns', { params: { pageNo, pageSize } })
}
