import type { Id } from '@/types/api'
import type { MenuSaveRequest, MenuTreeNode } from '@/types/system'
import { request } from '@/utils/request'

/**
 * 菜单维护(后端 SysMenuController,/system/menus)。
 *
 * 这是平台级能力:菜单全平台统一,只有平台超管能维护(前端文档 5.2 / 后端 5.1)。
 * 注意它返回的是全量菜单树(含平台专用菜单),与角色授权用的候选集不同。
 */
export function menuTree(status?: number | null, menuType?: number | null): Promise<MenuTreeNode[]> {
  return request.get<MenuTreeNode[]>('/system/menus/tree', { params: { status, menuType } })
}

export function createMenu(data: MenuSaveRequest): Promise<Id> {
  return request.post<Id>('/system/menus', data)
}

export function updateMenu(menuId: Id, data: MenuSaveRequest): Promise<void> {
  return request.put<void>(`/system/menus/${menuId}`, data)
}

export function deleteMenu(menuId: Id): Promise<void> {
  return request.delete<void>(`/system/menus/${menuId}`)
}
