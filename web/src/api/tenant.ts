import type { Id, PageResult } from '@/types/api'
import type {
  PackageChangeView,
  TenantCreateRequest,
  TenantCreateResponse,
  TenantView,
} from '@/types/system'
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

/**
 * 变更租户套餐。
 *
 * `effectiveDate` 只到日(`yyyy-MM-dd`):不传或传今天 = 立即生效;传未来日期则只登记,到那天由定时任务生效。
 */
export function changeTenantPackage(tenantId: Id, packageId: Id, effectiveDate?: string): Promise<void> {
  return request.put<void>(`/system/tenants/${tenantId}/package`, { packageId, effectiveDate })
}

/** 套餐变更历史(含预约未生效的登记记录),按登记时间倒序。 */
export function tenantPackageChanges(tenantId: Id, limit = 20): Promise<PackageChangeView[]> {
  return request.get<PackageChangeView[]>(`/system/tenants/${tenantId}/package-changes`, { params: { limit } })
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

/**
 * 改租户有效期,`expireTime` 传 null 表示改为不过期。
 *
 * 与启停同样是惰性生效:改成一个已过去的时间等价于立刻禁用,该租户用户会在下一次请求时被挡下。
 */
export function changeTenantExpireTime(tenantId: Id, expireTime: string | null): Promise<void> {
  return request.put<void>(`/system/tenants/${tenantId}/expire-time`, { expireTime })
}

/**
 * 注销租户(后端 PUT /system/tenants/{id}/close)。
 *
 * 必须**先禁用**再注销:注销的终点是数据被物理删除,一步到位太容易误点。
 * 注销后进入保留期(默认 3 个月),期间数据一行不动、可以取消;到期由任务清理。
 */
export function closeTenant(tenantId: Id): Promise<void> {
  return request.put<void>(`/system/tenants/${tenantId}/close`)
}

/** 取消注销(保留期内有效)。数据本来就还在,清掉清理时间即可。 */
export function cancelCloseTenant(tenantId: Id): Promise<void> {
  return request.put<void>(`/system/tenants/${tenantId}/close/cancel`)
}
