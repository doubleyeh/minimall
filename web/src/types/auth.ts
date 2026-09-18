import type { Id } from './api'

/** 令牌对。刷新成功时必须同一时刻覆盖写入这一对(前端文档 4.1)。 */
export interface TokenPair {
  token: string
  refreshToken: string
}

/** 登录响应(后端 LoginResponse,architecture.md 7.1.1)。字段名以后端为准,前端不改。 */
export interface LoginResult extends TokenPair {
  deviceId: string
  /** 注意是字符串:后端 Long 统一序列化为 string(见 types/api.ts 的说明) */
  userId: Id
  tenantId: Id
  isSuperUser: boolean
  mustChangePassword: boolean
  nickname: string
  /** 菜单标识,用于生成动态路由(5.2) */
  menus: string[]
  /** 权限码,供 v-perm 使用(5.3) */
  permCodes: string[]
}

/** `GET /auth/permissions` 的返回,以及 store 里保存的权限快照(5.1) */
export interface PermissionSnapshot {
  menus: string[]
  permCodes: string[]
}
