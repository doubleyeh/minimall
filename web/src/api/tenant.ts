import type { Id, PageResult } from '@/types/api'
import type { TenantCreateRequest, TenantCreateResponse, TenantView } from '@/types/system'
import { request } from '@/utils/request'

/** 租户管理(后端 SysTenantController,/system/tenants)。只有平台超管可用。 */

export interface TenantPageQuery {
  tenantCode?: string
  status?: number | null
  pageNo?: number
  pageSize?: number
}

export function pageTenants(query: TenantPageQuery): Promise<PageResult<TenantView>> {
  return request.get<PageResult<TenantView>>('/system/tenants', { params: query })
}

/** 建租户六步在后端一个事务里完成,返回的初始密码只出现一次。 */
export function createTenant(data: TenantCreateRequest): Promise<TenantCreateResponse> {
  return request.post<TenantCreateResponse>('/system/tenants', data)
}

export function changeTenantPackage(tenantId: Id, packageId: Id): Promise<void> {
  return request.put<void>(`/system/tenants/${tenantId}/package`, { packageId })
}

/**
 * 启用/禁用租户。
 *
 * 注意生效方式是惰性的:禁用后该租户用户的下一次请求才会被挡(缓存失效 + 会话清理),
 * 所以提示文案必须写"该租户用户将在下一次请求时退出",不能写"已立即踢出"(前端文档 6.4)。
 */
export function changeTenantStatus(tenantId: Id, status: number): Promise<void> {
  return request.put<void>(`/system/tenants/${tenantId}/status`, undefined, { params: { status } })
}
