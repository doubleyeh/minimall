import type { Id } from './api'
import type { MenuTreeNode } from './system'

/** 图形验证码:`image` 是可直接放进 <img src> 的 data URL。 */
export interface CaptchaView {
  captchaId: string
  image: string
}

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
  /** 导航菜单树(只有目录与页面),用于生成动态路由与侧边栏(5.2、7.4) */
  menuTree: MenuTreeNode[]
  /** 权限码,供 v-perm 使用(5.3) */
  permCodes: string[]
}

/** `GET /auth/permissions` 的返回,以及 store 里保存的权限快照(5.1) */
export interface PermissionSnapshot {
  menuTree: MenuTreeNode[]
  permCodes: string[]
}
