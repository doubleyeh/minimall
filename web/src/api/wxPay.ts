import type { Id, PageResult } from '@/types/api'
import type { WxPayConfigSaveRequest, WxPayConfigView } from '@/types/wxPay'
import { request } from '@/utils/request'

/** 微信支付配置管理(后端 SysWxPayConfigController,/system/wx-pay-configs)。只有平台超管可用。 */

export interface WxPayConfigPageQuery {
  tenantCode?: string
  status?: number | null
  pageNo?: number
  pageSize?: number
}

export function pageWxPayConfigs(query: WxPayConfigPageQuery): Promise<PageResult<WxPayConfigView>> {
  return request.get<PageResult<WxPayConfigView>>('/system/wx-pay-configs', { params: query })
}

export function createWxPayConfig(data: WxPayConfigSaveRequest): Promise<Id> {
  return request.post<Id>('/system/wx-pay-configs', data)
}

/** 密钥类字段留空表示保持原值。 */
export function updateWxPayConfig(tenantId: Id, data: WxPayConfigSaveRequest): Promise<void> {
  return request.put<void>(`/system/wx-pay-configs/${tenantId}`, data)
}

export function changeWxPayConfigStatus(tenantId: Id, status: number): Promise<void> {
  return request.put<void>(`/system/wx-pay-configs/${tenantId}/status`, undefined, { params: { status } })
}
