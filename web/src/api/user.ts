import type { Id, PageResult } from '@/types/api'
import type {
  UserCreateResponse,
  UserResetPasswordResponse,
  UserSaveRequest,
  UserView,
} from '@/types/system'
import { request } from '@/utils/request'

/** 用户管理(后端 SysUserController,/system/users) */

export interface UserPageQuery {
  username?: string
  deptId?: Id | null
  status?: number | null
  pageNo?: number
  pageSize?: number
}

export function pageUsers(query: UserPageQuery): Promise<PageResult<UserView>> {
  return request.get<PageResult<UserView>>('/system/users', { params: query })
}

export function getUser(userId: Id): Promise<UserView> {
  return request.get<UserView>(`/system/users/${userId}`)
}

export function createUser(data: UserSaveRequest): Promise<UserCreateResponse> {
  return request.post<UserCreateResponse>('/system/users', data)
}

export function updateUser(userId: Id, data: UserSaveRequest): Promise<void> {
  return request.put<void>(`/system/users/${userId}`, data)
}

export function deleteUser(userId: Id): Promise<void> {
  return request.delete<void>(`/system/users/${userId}`)
}

export function changeUserStatus(userId: Id, status: number): Promise<void> {
  return request.put<void>(`/system/users/${userId}/status`, undefined, { params: { status } })
}

/** 重置密码:返回的明文只出现一次,必须让操作人立即保存(前端文档 6.3)。 */
export function resetUserPassword(userId: Id): Promise<UserResetPasswordResponse> {
  return request.post<UserResetPasswordResponse>(`/system/users/${userId}/password/reset`)
}

export function unlockUser(userId: Id): Promise<void> {
  return request.post<void>(`/system/users/${userId}/unlock`)
}
