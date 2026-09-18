import type { Id, PageResult } from '@/types/api'
import type { MenuTreeNode, PackageSaveRequest, PackageView } from '@/types/system'
import { request } from '@/utils/request'

/** 套餐管理(后端 SysPackageController,/system/packages)。平台级能力。 */

export interface PackagePageQuery {
  packageName?: string
  status?: number | null
  pageNo?: number
  pageSize?: number
}

export function pagePackages(query: PackagePageQuery): Promise<PageResult<PackageView>> {
  return request.get<PageResult<PackageView>>('/system/packages', { params: query })
}

export function createPackage(data: PackageSaveRequest): Promise<Id> {
  return request.post<Id>('/system/packages', data)
}

export function updatePackage(packageId: Id, data: PackageSaveRequest): Promise<void> {
  return request.put<void>(`/system/packages/${packageId}`, data)
}

/** 套餐不做物理删除,只能禁用(后端 5.6)。 */
export function disablePackage(packageId: Id): Promise<void> {
  return request.put<void>(`/system/packages/${packageId}/disable`)
}

/** 套餐可选菜单树(全部非平台专用菜单)。 */
export function packageGrantableMenus(packageId: Id): Promise<MenuTreeNode[]> {
  return request.get<MenuTreeNode[]>(`/system/packages/${packageId}/menus/grantable`)
}

export function packageMenus(packageId: Id): Promise<Id[]> {
  return request.get<Id[]>(`/system/packages/${packageId}/menus`)
}

/**
 * 保存套餐菜单。
 * 保存完成即意味着已对所有绑定该套餐的租户同步完成(后端 4.8.1),租户多时这个请求会慢,不要当成"仅保存"。
 */
export function savePackageMenus(packageId: Id, menuIds: Id[]): Promise<void> {
  return request.put<void>(`/system/packages/${packageId}/menus`, { menuIds })
}

/** 运维出口:让停在"未同步"状态的租户收敛。 */
export function resyncPackage(packageId: Id): Promise<void> {
  return request.post<void>(`/system/packages/${packageId}/resync`)
}
