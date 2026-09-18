import type { Id, PageResult } from '@/types/api'
import type { MenuTreeNode, RoleCreateRequest, RoleView } from '@/types/system'
import { request } from '@/utils/request'

/** 角色与角色授权(后端 SysRoleController,/system/roles) */

export interface RolePageQuery {
  roleName?: string
  status?: number | null
  pageNo?: number
  pageSize?: number
}

export function pageRoles(query: RolePageQuery): Promise<PageResult<RoleView>> {
  return request.get<PageResult<RoleView>>('/system/roles', { params: query })
}

export function createRole(data: RoleCreateRequest): Promise<Id> {
  return request.post<Id>('/system/roles', data)
}

export function updateRole(roleId: Id, data: RoleCreateRequest): Promise<void> {
  return request.put<void>(`/system/roles/${roleId}`, data)
}

export function deleteRole(roleId: Id): Promise<void> {
  return request.delete<void>(`/system/roles/${roleId}`)
}

export function changeRoleStatus(roleId: Id, status: number): Promise<void> {
  return request.put<void>(`/system/roles/${roleId}/status`, undefined, { params: { status } })
}

/**
 * 授权候选菜单树:按该租户套餐过滤后的集合,与登录响应的 menus 不是一回事
 * (前端文档 6.1 第 1 条明确禁止用 menus 渲染授权树)。
 */
export function grantableMenus(roleId: Id): Promise<MenuTreeNode[]> {
  return request.get<MenuTreeNode[]>(`/system/roles/${roleId}/menus/grantable`)
}

/** 该角色已授权的菜单 ID(编辑回显)。 */
export function grantedMenus(roleId: Id): Promise<Id[]> {
  return request.get<Id[]>(`/system/roles/${roleId}/menus`)
}

/** 保存授权。提交内容必须包含全部祖先节点(前端文档 6.1 第 2 条),后端不做静默过滤。 */
export function grantMenus(roleId: Id, menuIds: Id[]): Promise<void> {
  return request.put<void>(`/system/roles/${roleId}/menus`, { menuIds })
}
