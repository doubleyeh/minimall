import type { LoginResult, PermissionSnapshot, TokenPair } from '@/types/auth'
import { request } from '@/utils/request'

/**
 * 认证相关接口(后端 AuthController,architecture.md 7.1)。
 *
 * 只声明请求与类型,**不写业务逻辑、不弹提示**(前端文档 2 的 api 层约定)。
 */
export interface LoginPayload {
  tenantCode: string
  username: string
  /** 明文提交:禁止前端做 md5 或任何加密(4.4) */
  password: string
  deviceId?: string
}

export function login(payload: LoginPayload): Promise<LoginResult> {
  return request.post<LoginResult>('/auth/login', payload)
}

/**
 * 登出。**请求体必须带 refreshToken**(4.6):不带的话后端会撤销该用户**全部设备**的令牌,
 * 用户在其他端的登录会被一起踢掉。
 */
export function logout(refreshToken: string): Promise<void> {
  return request.post<void>('/auth/logout', { refreshToken })
}

/** 权限快照(5.1):页面刷新、403 之后重建菜单靠它。 */
export function fetchPermissions(): Promise<PermissionSnapshot> {
  return request.get<PermissionSnapshot>('/auth/permissions')
}

/** 修改密码。成功后后端会让该用户全部会话失效,前端必须重新登录(4.5)。 */
export function changePassword(payload: {
  oldPassword: string
  newPassword: string
}): Promise<void> {
  return request.post<void>('/auth/password', payload)
}

/** 显式刷新令牌。常规刷新由 `utils/request.ts` 内部完成,这里只给特殊场景用。 */
export function refresh(refreshToken: string): Promise<TokenPair> {
  return request.post<TokenPair>('/auth/refresh', { refreshToken })
}
