import type { Id } from './api'

/**
 * 系统管理相关的类型,与后端 DTO 一一对应(字段名以后端为准,前端不得改名)。
 * 后端把 Long 统一序列化成字符串,所以所有 id 类字段都是 Id(= string)。
 */

// ——— 用户 ———

export interface UserView {
  id: Id
  username: string
  nickname: string
  phone: string | null
  deptId: Id | null
  deptName: string | null
  status: number
  lockTime: string | null
  mustChangePassword: boolean
  createTime: string | null
}

export interface UserSaveRequest {
  username: string
  /** 新增时不传则后端随机生成;修改时后端忽略该字段 */
  password?: string | null
  nickname?: string | null
  phone?: string | null
  deptId?: Id | null
  roleIds?: Id[]
  status?: number | null
}

export interface UserCreateResponse {
  userId: Id
  username: string
  /** 仅返回一次:前端必须弹窗提示立即保存(前端文档 6.3) */
  initialPassword: string | null
  mustChangePassword: boolean
}

export interface UserResetPasswordResponse {
  userId: Id
  username: string
  initialPassword: string | null
  mustChangePassword: boolean
}

// ——— 角色 ———

export interface RoleView {
  id: Id
  roleKey: string
  roleName: string
  dataScope: number
  isDefault: number
  status: number
  createTime: string | null
}

export interface RoleCreateRequest {
  roleKey: string
  roleName: string
  /** 1-仅本人 2-本部门 3-本部门及以下 4-自定义部门 5-全部 */
  dataScope: number
  /** 仅 dataScope = 4 时有意义,且必填 */
  deptIds?: Id[] | null
  status?: number | null
}

// ——— 部门 ———

export interface DeptTreeNode {
  id: Id
  parentId: Id
  ancestors: string
  deptName: string
  sortOrder: number
  status: number
  children?: DeptTreeNode[]
}

export interface DeptSaveRequest {
  /** 0 表示根部门 */
  parentId: Id
  deptName: string
  sortOrder: number
  status: number
}

// ——— 菜单 ———

export interface MenuTreeNode {
  id: Id
  parentId: Id
  menuName: string
  /** 1-目录 2-页面 3-按钮 */
  menuType: number
  routePath: string | null
  permCode: string | null
  sortOrder: number
  status: number
  children?: MenuTreeNode[]
}

export interface MenuSaveRequest {
  parentId: Id
  menuName: string
  menuType: number
  routePath?: string | null
  permCode?: string | null
  icon?: string | null
  sortOrder: number
  status: number
  isPlatform?: boolean
}

// ——— 套餐 ———

export interface PackageView {
  id: Id
  packageName: string
  remark: string | null
  status: number
  menuCount: number
  tenantCount: number
  createTime: string | null
}

export interface PackageSaveRequest {
  packageName: string
  remark?: string | null
  status: number
}

// ——— 租户 ———

export interface TenantView {
  id: Id
  tenantCode: string
  tenantName: string
  status: number
  packageId: Id | null
  packageName: string | null
  expireTime: string | null
  createTime: string | null
}

export interface TenantCreateRequest {
  tenantCode: string
  tenantName: string
  packageId: Id
  /** 为空表示不过期 */
  expireTime?: string | null
  adminUsername: string
  adminNickname?: string | null
  /** 为空则由后端随机生成并在响应里返回一次 */
  adminPassword?: string | null
}

export interface TenantCreateResponse {
  tenantId: Id
  tenantCode: string
  adminUsername: string
  adminUserId: Id
  defaultRoleId: Id
  rootDeptId: Id
  initialPassword: string | null
  mustChangePassword: boolean
}
