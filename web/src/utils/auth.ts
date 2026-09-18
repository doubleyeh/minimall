import type { TokenPair } from '@/types/auth'

const TOKEN_KEY = 'mini-mall:token'
const REFRESH_TOKEN_KEY = 'mini-mall:refreshToken'

/**
 * 令牌存取(前端文档 4.1)。
 *
 * 两条硬规则,都是为了避开"看起来能用、偶发登出"的坑:
 * 1. 每次现读 localStorage,不在模块变量里缓存。多标签页场景下别的标签会写新令牌,
 *    模块缓存会让本标签一直拿着过期值 —— 表现为"莫名其妙被登出",且无法复现;
 * 2. 写入必须是同一时刻覆盖一对:先写 token 再写 refreshToken 之间如果被打断,
 *    就会出现"新 token + 旧 refreshToken"的状态,下一次刷新必然撞上重放检测(后端会踢掉全部会话)。
 */
export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function setTokens(pair: TokenPair): void {
  localStorage.setItem(TOKEN_KEY, pair.token)
  localStorage.setItem(REFRESH_TOKEN_KEY, pair.refreshToken)
}

export function clearTokens(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
}

/** 仅"记住租户编码"用(前端文档 4.4):禁止记忆用户名与密码。 */
const TENANT_CODE_KEY = 'mini-mall:tenantCode'

export function getRememberedTenantCode(): string {
  return localStorage.getItem(TENANT_CODE_KEY) ?? ''
}

export function rememberTenantCode(tenantCode: string): void {
  localStorage.setItem(TENANT_CODE_KEY, tenantCode)
}
